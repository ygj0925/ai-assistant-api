/*
 * Copyright (c) 2022-present Charles7c Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package top.continew.admin.assistant.analysis;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.continew.admin.assistant.account.AssistantAccounts;
import top.continew.admin.assistant.account.AssistantAccounts.User;
import top.continew.admin.assistant.account.AssistantApiException;
import top.continew.admin.assistant.privacy.AssistantDeletionJournal;
import top.continew.admin.assistant.privacy.AssistantErasure;
import top.continew.admin.assistant.project.AssistantProjectResources;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Stores reproducible numeric reports for immutable, authorized private materials.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
@Service
@ConditionalOnProperty(name = "assistant.enabled", havingValue = "true")
public class AssistantDataAnalysis {

    private static final String SELECT =
        """
            SELECT a.id, a.project_id, a.resource_id, a.value_column, a.row_count, a.value_count,
                a.min_value, a.max_value, a.sum_value, a.mean_value, a.series_json, a.created_at,
                a.duration_millis, a.algorithm_version, a.artifact_csv
            FROM assistant_data_analysis a
            JOIN assistant_project_resource r ON r.workspace_id = a.workspace_id AND r.account_id = a.account_id
                AND r.project_id = a.project_id AND r.id = a.resource_id AND r.deleted_at IS NULL
            JOIN assistant_project p ON p.workspace_id = a.workspace_id AND p.account_id = a.account_id
                AND p.id = a.project_id AND p.deleted_at IS NULL
            WHERE a.workspace_id = ? AND a.account_id = ?
            """;
    private static final TypeReference<List<Point>> POINTS_TYPE = new TypeReference<>() {
    };

    private final JdbcTemplate jdbc;
    private final AssistantAccounts accounts;
    private final AssistantProjectResources resources;
    private final AssistantDeletionJournal journal;
    private final AssistantErasure erasure;
    private final ObjectMapper json;
    private final boolean enabled;
    private final boolean resourcesEnabled;

    /**
     * Connects the isolated built-in computation to authorized material storage.
     *
     * @param jdbc scoped product data access
     * @param accounts account lifecycle
     * @param resources private project material data
     * @param journal independent deletion readiness
     * @param erasure material revocations
     * @param json typed report persistence
     * @param enabled configured numeric analysis flag
     * @param resourcesEnabled configured project materials flag
     */
    public AssistantDataAnalysis(JdbcTemplate jdbc, AssistantAccounts accounts,
        AssistantProjectResources resources, AssistantDeletionJournal journal,
        AssistantErasure erasure, ObjectMapper json,
        @Value("${assistant.analysis.enabled:false}") boolean enabled,
        @Value("${assistant.project-resources.enabled:false}") boolean resourcesEnabled) {
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.resources = resources;
        this.journal = journal;
        this.erasure = erasure;
        this.json = json;
        this.enabled = enabled;
        this.resourcesEnabled = resourcesEnabled;
    }

    /**
     * Computes and stores a report or returns the existing report of this version.
     *
     * @param owner authenticated owner in an authorized workspace
     * @param projectId owned project
     * @param resourceId owned immutable text material
     * @param requestedColumn exact header name
     * @return bounded response summary
     */
    @Transactional
    public Summary create(User owner, String projectId, String resourceId, String requestedColumn) {
        requireReady();
        String column = NumericCsvAnalysis.column(requestedColumn);
        accounts.lockActive(owner.id());
        AssistantProjectResources.Resource source = resources.get(owner, projectId, resourceId);
        List<Stored> previous = jdbc.query(
            SELECT
                + """
                    AND a.project_id = ? AND a.resource_id = ? AND a.value_column = ? AND a.algorithm_version = ?
                    """,
            row(), owner.workspaceId(), owner.id(), projectId, resourceId, column,
            NumericCsvAnalysis.VERSION);
        if (!previous.isEmpty()) {
            return previous.get(0).summary();
        }

        NumericCsvAnalysis.Result result = NumericCsvAnalysis.run(source.content(), column,
            System::nanoTime);
        String id = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        jdbc.update(
            """
                INSERT INTO assistant_data_analysis
                    (id, workspace_id, account_id, project_id, resource_id, value_column, row_count, value_count,
                     min_value, max_value, sum_value, mean_value, series_json, artifact_csv, created_at,
                     duration_millis, algorithm_version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
            id, owner.workspaceId(), owner.id(), projectId, resourceId, column, result.rowCount(),
            result.valueCount(), result.min(), result.max(), result.sum(), result.mean(),
            writeSeries(result.series()), result.artifact(), now, result.durationMillis(),
            NumericCsvAnalysis.VERSION);
        return new Summary(id, projectId, resourceId, column, result.rowCount(),
            result.valueCount(),
            result.min(), result.max(), result.sum(), result.mean(), result.series(),
            Instant.ofEpochMilli(now), result.durationMillis(), NumericCsvAnalysis.VERSION);
    }

    /**
     * Reads the authorized report and its chart data.
     *
     * @param owner authenticated owner in an authorized workspace
     * @param id report identifier
     * @return report summary
     */
    public Summary get(User owner, String id) {
        return visible(owner, id).summary();
    }

    /**
     * Loads the protected, numeric-only output artifact.
     *
     * @param owner authenticated owner in an authorized workspace
     * @param id report identifier
     * @return UTF-8 CSV bytes
     */
    public byte[] artifact(User owner, String id) {
        return visible(owner, id).artifact().getBytes(StandardCharsets.UTF_8);
    }

    private Stored visible(User owner, String id) {
        requireReady();
        requireId(id);
        return jdbc.query(SELECT + " AND a.id = ?", row(), owner.workspaceId(), owner.id(), id)
            .stream().filter(report -> !erasure.blocksProjectResource(owner,
                report.summary().resourceId()))
            .findFirst()
            .orElseThrow(() -> new AssistantApiException(HttpStatus.NOT_FOUND, "NOT_FOUND",
                "Analysis not found."));
    }

    private RowMapper<Stored> row() {
        return (source, index) -> new Stored(new Summary(source.getString("id"),
            source.getString("project_id"), source.getString("resource_id"),
            source.getString("value_column"), source.getInt("row_count"),
            source.getInt("value_count"),
            source.getString("min_value"), source.getString("max_value"),
            source.getString("sum_value"),
            source.getString("mean_value"), readSeries(source.getString("series_json")),
            Instant.ofEpochMilli(source.getLong("created_at")), source.getLong("duration_millis"),
            source.getString("algorithm_version")), source.getString("artifact_csv"));
    }

    private List<Point> readSeries(String value) {
        try {
            return json.readValue(value, POINTS_TYPE);
        } catch (JsonProcessingException failure) {
            throw unavailable();
        }
    }

    private String writeSeries(List<Point> points) {
        try {
            return json.writeValueAsString(points);
        } catch (JsonProcessingException failure) {
            throw unavailable();
        }
    }

    private void requireReady() {
        if (!enabled || !resourcesEnabled || !journal.enabled()) {
            throw new AssistantApiException(HttpStatus.FORBIDDEN, "CAPABILITY_UNAVAILABLE",
                "Data analysis is unavailable.");
        }
        if (!journal.acceptsErasure()) {
            throw unavailable();
        }
    }

    private static void requireId(String id) {
        try {
            if (id != null && id.length() == 36 && UUID.fromString(id).toString().equals(id)) {
                return;
            }
        } catch (IllegalArgumentException failure) {
            throw invalidId();
        }
        throw invalidId();
    }

    private static AssistantApiException invalidId() {
        return new AssistantApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
            "Invalid analysis identifier.");
    }

    private static AssistantApiException unavailable() {
        return new AssistantApiException(HttpStatus.SERVICE_UNAVAILABLE, "INTERNAL_ERROR",
            "Data analysis is temporarily unavailable.");
    }

    /**
     * A nonempty selected value and its one-based CSV data row.
     *
     * @param row data row ordinal, excluding the header
     * @param value canonical numeric text
     */
    public record Point(int row, String value) {
    }

    /**
     * Content-free diagnostics for the completed calculation.
     *
     * @param blankValuesSkipped blank cells excluded from calculations
     * @param chartTruncated whether chart sampling removed numeric points
     */
    public record LogSummary(int blankValuesSkipped, boolean chartTruncated) {
    }

    /**
     * Reproducible report with textual decimal values and a bounded chart sample.
     *
     * @param id report identifier
     * @param projectId owning project
     * @param resourceId immutable source material
     * @param column chosen header
     * @param rowCount all CSV data rows
     * @param valueCount nonempty numeric rows
     * @param min smallest value
     * @param max largest value
     * @param sum exact sum
     * @param mean mean rounded to 12 fractional places
     * @param series first 32 numeric values
     * @param createdAt report creation time
     * @param durationMillis computation duration
     * @param algorithmVersion built-in calculation version
     */
    public record Summary(String id, String projectId, String resourceId, String column,
        int rowCount,
        int valueCount, String min, String max, String sum, String mean, List<Point> series,
        Instant createdAt, long durationMillis, String algorithmVersion) {

        /**
         * Reports excluded rows and chart sampling without raw input.
         *
         * @return aggregate run diagnostics
         */
        @JsonProperty("logSummary")
        public LogSummary logSummary() {
            return new LogSummary(rowCount - valueCount, valueCount > series.size());
        }

        @Override
        public String toString() {
            return "Summary[protected]";
        }
    }

    private record Stored(Summary summary, String artifact) {
    }
}

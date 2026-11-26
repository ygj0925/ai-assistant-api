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

package top.continew.admin.assistant.privacy;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import top.continew.admin.assistant.account.AssistantAccounts.Preferences;
import top.continew.admin.assistant.account.AssistantAccounts.Principal;
import top.continew.admin.assistant.account.AssistantApiException;
import top.continew.admin.assistant.analysis.AssistantDataAnalysis.Point;
import top.continew.admin.assistant.analysis.AssistantDataAnalysis.Summary;
import top.continew.admin.assistant.artifact.AssistantArtifactImages;
import top.continew.admin.assistant.artifact.AssistantArtifacts;
import top.continew.admin.assistant.attachment.AssistantAttachments;
import top.continew.admin.assistant.attachment.AttachmentImageEditProof;
import top.continew.admin.assistant.attachment.AttachmentImageEditProof.Origin;
import top.continew.admin.assistant.attachment.AttachmentImageEditProof.Proof;
import top.continew.admin.assistant.catalog.AssistantCatalog.CatalogRelease;
import top.continew.admin.assistant.catalog.AssistantCatalog.Report;
import top.continew.admin.assistant.customassistant.AssistantCustomAssistants;
import top.continew.admin.assistant.customassistant.AssistantCustomAssistants.Version;
import top.continew.admin.assistant.privacy.AssistantDeletionJournal.ExportMemoryPolicy;
import top.continew.admin.assistant.reminder.AssistantReminders.Occurrence;
import top.continew.admin.assistant.reminder.AssistantReminders.QuietHours;
import top.continew.admin.assistant.reminder.AssistantReminders.Reminder;
import top.continew.admin.assistant.run.AssistantRunContext;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Creates bounded download pages for an authenticated consumer's live, owned workspace data.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
@Service
@ConditionalOnProperty(name = "assistant.enabled", havingValue = "true")
public class AssistantExport {

    static final int PAGE_SIZE = 64;
    static final int MAX_BYTES = 8 * 1024 * 1024;

    private static final String PROFILE =
        """
            SELECT a.id, a.username, a.locale, a.theme, a.created_at
            FROM assistant_account a
            JOIN assistant_session s ON s.account_id = a.id
            JOIN assistant_workspace_member wm ON wm.account_id = a.id
            WHERE a.id = ? AND wm.workspace_id = ? AND s.id = ? AND s.expires_at > ? AND a.deleted_at IS NULL
            """;
    private static final String PROJECTS = """
        SELECT p.id, p.name, p.description, p.created_at, p.updated_at
        FROM assistant_project p
        WHERE p.account_id = ? AND p.workspace_id = ? AND p.deleted_at IS NULL
            AND p.id > ? ORDER BY p.id LIMIT ?
        """;
    private static final String CONVERSATIONS =
        """
            SELECT c.id, c.title, c.pinned, c.archived, c.created_at, c.updated_at, c.project_id
            FROM assistant_conversation c
            WHERE c.account_id = ? AND c.workspace_id = ? AND c.deleted_at IS NULL AND c.read_locked = FALSE
                AND c.id > ? ORDER BY c.id LIMIT ?
            """;
    private static final String MESSAGES =
        """
            SELECT m.id, m.conversation_id, m.parent_id, m.role, m.created_at,
                CASE WHEN CHAR_LENGTH(m.content) <= 8192 THEN m.content END AS content
            FROM assistant_chat_message m JOIN assistant_conversation c ON c.id = m.conversation_id
            WHERE c.account_id = ? AND c.workspace_id = ? AND c.deleted_at IS NULL AND c.read_locked = FALSE
                AND m.id > ? ORDER BY m.id LIMIT ?
            """;
    private static final String RUNS =
        """
            SELECT r.id, r.conversation_id, r.prompt_message_id, r.response_message_id, r.state,
                r.model_id, r.model_version, r.fallback_from_model_id, r.created_at, r.updated_at,
                r.finished_at, r.error_code, r.context_budget, r.context_used, r.context_turns, r.context_trimmed,
                CASE WHEN CHAR_LENGTH(r.content) <= 8192 THEN r.content END AS content
            FROM assistant_run r
            JOIN assistant_conversation c ON c.id = r.conversation_id AND c.workspace_id = r.workspace_id
                AND c.account_id = r.account_id
            WHERE c.account_id = ? AND c.workspace_id = ? AND c.deleted_at IS NULL
                AND c.read_locked = FALSE
                AND r.id > ? ORDER BY r.id LIMIT ?
            """;
    private static final String RESOURCE_LINEAGE = """
        JOIN assistant_project p ON p.id = r.project_id AND p.workspace_id = r.workspace_id
            AND p.account_id = r.account_id AND p.deleted_at IS NULL
        """;
    private static final String RESOURCES = """
        SELECT r.id, r.project_id, r.name, r.created_at,
            CASE WHEN OCTET_LENGTH(r.content) BETWEEN 1 AND 16384 THEN r.content END AS content
        FROM assistant_project_resource r
        """ + RESOURCE_LINEAGE + """
        WHERE r.account_id = ? AND r.workspace_id = ? AND r.deleted_at IS NULL
            AND r.id > ? ORDER BY r.id LIMIT ?
        """;
    private static final String ANALYSES =
        """
            SELECT a.id, a.project_id, a.resource_id, a.value_column, a.row_count, a.value_count,
                a.min_value, a.max_value, a.sum_value, a.mean_value, a.created_at, a.duration_millis,
                a.algorithm_version,
                CASE WHEN OCTET_LENGTH(a.series_json) BETWEEN 1 AND 8192 THEN a.series_json END AS series_json,
                CASE WHEN OCTET_LENGTH(a.artifact_csv) BETWEEN 1 AND 16384 THEN a.artifact_csv END AS artifact_csv
            FROM assistant_data_analysis a
            JOIN assistant_project_resource r ON r.id = a.resource_id AND r.workspace_id = a.workspace_id
                AND r.account_id = a.account_id AND r.project_id = a.project_id AND r.deleted_at IS NULL
            """
            + RESOURCE_LINEAGE + """
                WHERE a.account_id = ? AND a.workspace_id = ? AND a.id > ? ORDER BY a.id LIMIT ?
                """;
    private static final String MEMORY_PROJECT = """
        LEFT JOIN assistant_project p ON p.id = s.project_id AND p.workspace_id = s.workspace_id
            AND p.account_id = s.account_id AND p.deleted_at IS NULL
        """;
    private static final String VISIBLE_MEMORY_SCOPE = """
        ((s.scope_kind = 'personal' AND s.project_id IS NULL AND s.scope_id = s.workspace_id)
            OR (s.scope_kind = 'project' AND p.id = s.scope_id))
        """;
    private static final String MEMORY_SETTINGS = """
        SELECT s.scope_id, s.scope_kind, s.project_id, s.memory_enabled, s.reference_history,
            s.memory_epoch, s.history_epoch, s.instructions_epoch,
            CASE WHEN OCTET_LENGTH(s.instructions) <= 8000 THEN s.instructions END AS instructions
        FROM assistant_memory_settings s
        """ + MEMORY_PROJECT + """
        WHERE s.account_id = ? AND s.workspace_id = ? AND
        """ + VISIBLE_MEMORY_SCOPE;
    private static final String MEMORY_SETTINGS_CURSOR = """
        AND s.scope_id > ? ORDER BY s.scope_id LIMIT ?
        """;
    private static final String PERSONAL_MEMORY_SETTINGS = MEMORY_SETTINGS
        + " AND s.scope_kind = 'personal' " + MEMORY_SETTINGS_CURSOR;
    private static final String PROJECT_MEMORY_SETTINGS = MEMORY_SETTINGS
        + " AND s.scope_kind = 'project' " + MEMORY_SETTINGS_CURSOR;
    private static final String MEMORY_ENTRIES =
        """
            SELECT e.id, s.scope_kind, s.project_id, e.created_at,
                CASE WHEN OCTET_LENGTH(e.content) BETWEEN 1 AND 8000 THEN e.content END AS content
            FROM assistant_memory_entry e
            JOIN assistant_memory_settings s ON s.account_id = e.account_id AND s.workspace_id = e.workspace_id
                AND s.scope_kind = e.scope_kind AND s.scope_id = e.scope_id
            """
            + MEMORY_PROJECT + """
                WHERE e.account_id = ? AND e.workspace_id = ? AND e.deleted_at IS NULL AND
                """ + VISIBLE_MEMORY_SCOPE + """
                AND e.id > ? ORDER BY e.id LIMIT ?
                """;
    private static final String ATTACHMENTS =
        """
            SELECT a.id, a.project_id, a.size_bytes, a.width, a.height, a.created_at,
                a.media_kind, a.sample_rate, a.channel_count, a.duration_millis,
            """ + AttachmentImageEditProof.PROJECTION + """
            FROM assistant_attachment a
            JOIN assistant_project p ON p.id = a.project_id AND p.workspace_id = a.workspace_id
                AND p.account_id = a.account_id AND p.deleted_at IS NULL
            """ + AttachmentImageEditProof.joins("a")
            + """
                WHERE a.account_id = ? AND a.workspace_id = ?
                    AND a.status IN ('approved','wave_approved','pdf_approved','edit_approved')
                    AND ((a.media_kind <> 'EDIT' AND image_edit.output_attachment_id IS NULL)
                        OR (a.media_kind = 'EDIT' AND image_edit_source.id IS NOT NULL AND image_edit_member.account_id IS NOT NULL))
                    AND a.approved_digest IS NOT NULL AND a.id > ? ORDER BY a.id LIMIT ?
                """;
    private static final String CUSTOM_SCOPE =
        """
            WHERE a.account_id = ? AND a.workspace_id = ? AND (a.project_id IS NULL OR EXISTS (
                SELECT 1 FROM assistant_project p WHERE p.id = a.project_id AND p.workspace_id = a.workspace_id
                    AND p.account_id = a.account_id AND p.deleted_at IS NULL))
            """;
    private static final String CUSTOM_ASSISTANTS =
        """
            SELECT a.id, a.project_id, a.head_version_id, a.head_epoch, a.created_at, a.updated_at,
                v.name, v.version_no
            FROM assistant_custom_assistant a
            JOIN assistant_custom_assistant_version v ON v.assistant_id = a.id AND v.id = a.head_version_id
            """
            + CUSTOM_SCOPE + "AND a.id > ? ORDER BY a.id LIMIT ?";
    private static final String CUSTOM_VERSIONS = """
        SELECT v.id, v.assistant_id, v.version_no, v.name, v.tool_id, v.created_at,
            CASE WHEN OCTET_LENGTH(v.instructions) <= 8000 THEN v.instructions END AS instructions,
            prior.id AS restored_from_version_id
        FROM assistant_custom_assistant_version v
        JOIN assistant_custom_assistant a ON a.id = v.assistant_id
        LEFT JOIN assistant_custom_assistant_version prior ON prior.assistant_id = a.id
            AND prior.id = v.restored_from_version_id
        """ + CUSTOM_SCOPE + "AND v.id > ? ORDER BY v.id LIMIT ?";
    private static final String CUSTOM_RESOURCES =
        """
            SELECT s.version_id, r.id AS resource_id FROM assistant_custom_assistant_source s
            JOIN assistant_project_resource r ON r.id = s.resource_id AND r.account_id = s.account_id
                AND r.workspace_id = s.workspace_id AND r.project_id = s.project_id AND r.deleted_at IS NULL
            WHERE s.account_id = ? AND s.workspace_id = ? AND s.version_id IN (%s)
            ORDER BY s.version_id, s.position_no
            """;
    private static final String REMINDERS = """
        SELECT r.id, r.title, r.starts_at_local, r.time_zone, r.cadence, r.quiet_start, r.quiet_end,
            r.status, r.config_epoch, r.created_at, r.updated_at
        FROM assistant_reminder r
        WHERE r.account_id = ? AND r.workspace_id = ? AND r.id > ? ORDER BY r.id LIMIT ?
        """;
    private static final String REMINDER_HISTORY = """
        SELECT o.id, o.reminder_id, r.title, o.scheduled_local_at, o.planned_at, o.delivery_at,
            o.occurred_at, o.outcome, o.read_at
        FROM assistant_reminder_occurrence o JOIN assistant_reminder r ON r.id = o.reminder_id
        WHERE r.account_id = ? AND r.workspace_id = ? AND o.id > ? ORDER BY o.id LIMIT ?
        """;
    private static final String ARTIFACT_SCOPE =
        """
            WHERE a.account_id = ? AND a.workspace_id = ?
                AND (a.project_id IS NULL OR EXISTS (
                    SELECT 1 FROM assistant_project p WHERE p.id = a.project_id
                        AND p.account_id = a.account_id AND p.workspace_id = a.workspace_id AND p.deleted_at IS NULL))
                AND (a.source_conversation_id IS NULL OR EXISTS (
                    SELECT 1 FROM assistant_conversation c WHERE c.id = a.source_conversation_id
                        AND c.account_id = a.account_id AND c.workspace_id = a.workspace_id
                        AND c.deleted_at IS NULL AND c.read_locked = FALSE))
                AND (a.format <> 'image' OR (approved_image.id IS NOT NULL
                    AND ((approved_image.media_kind = 'PNG' AND image_edit.output_attachment_id IS NULL)
                        OR (approved_image.media_kind = 'EDIT'
                            AND image_edit_source.id IS NOT NULL AND image_edit_member.account_id IS NOT NULL))))
            """;
    private static final String ARTIFACTS =
        """
            SELECT a.id, a.project_id, a.source_message_id, a.source_conversation_id, a.format, a.head_version_id,
                a.created_at, a.updated_at, v.version_no, v.title, v.size_bytes,
            """
            + AssistantArtifactImages.SOURCE_PROJECTION
            + """
                FROM assistant_artifact a
                JOIN assistant_artifact_version v ON v.artifact_id = a.id AND v.id = a.head_version_id
                """
            + AssistantArtifactImages.SOURCE_JOIN
            + ARTIFACT_SCOPE + "AND a.id > ? ORDER BY a.id LIMIT ?";
    private static final String ARTIFACT_VERSIONS =
        """
            SELECT v.id, v.artifact_id, a.source_conversation_id, a.format, v.version_no, v.title, v.size_bytes,
                v.created_at,
                prior.id AS restored_from_version_id,
                CASE WHEN OCTET_LENGTH(v.content) <= 32768 AND OCTET_LENGTH(v.content) = v.size_bytes
                    THEN v.content END AS content,
            """
            + AssistantArtifactImages.SOURCE_PROJECTION + """
                FROM assistant_artifact_version v JOIN assistant_artifact a ON a.id = v.artifact_id
                LEFT JOIN assistant_artifact_version prior ON prior.artifact_id = v.artifact_id
                    AND prior.id = v.restored_from_version_id
                """ + AssistantArtifactImages.SOURCE_JOIN
            + ARTIFACT_SCOPE + "AND v.id > ? ORDER BY v.id LIMIT ?";
    private static final String CATALOG_AUTHOR_COLUMNS = """
        e.account_id AS author_account_id, e.workspace_id AS author_workspace_id,
        e.membership_revision AS author_membership_revision,
        CASE WHEN e.kind = 'assistant' THEN e.source_id END AS source_assistant_id
        """;
    private static final String CATALOG_SOURCE =
        """
            JOIN assistant_catalog_entry e ON e.id = r.entry_id AND e.workspace_id = r.workspace_id
            JOIN assistant_workspace_member m ON m.account_id = e.account_id AND m.workspace_id = e.workspace_id
                AND m.membership_revision = e.membership_revision
            JOIN assistant_account owner_account ON owner_account.id = e.account_id AND owner_account.deleted_at IS NULL
            LEFT JOIN assistant_custom_assistant a ON a.id = e.source_id AND a.account_id = e.account_id
                AND a.workspace_id = e.workspace_id AND e.kind = 'assistant'
            LEFT JOIN assistant_custom_assistant_version v ON v.assistant_id = a.id AND v.id = r.source_version_id
            """;
    private static final String CATALOG_LIVE_ORIGIN =
        """
            AND (e.kind = 'connector' OR (v.id IS NOT NULL AND (a.project_id IS NULL OR EXISTS (
                SELECT 1 FROM assistant_project p WHERE p.id = a.project_id AND p.account_id = e.account_id
                    AND p.workspace_id = e.workspace_id AND p.deleted_at IS NULL))))
            """;
    private static final String CATALOG_AUTHORED_RELEASES = """
        SELECT r.id, r.entry_id, e.kind, r.state, r.version_number, r.title, r.summary,
            r.submitted_at, r.decided_at,
        """ + CATALOG_AUTHOR_COLUMNS + """
        FROM assistant_catalog_release r
        """ + CATALOG_SOURCE + """
        WHERE e.account_id = ? AND e.workspace_id = ?
        """ + CATALOG_LIVE_ORIGIN + "AND r.id > ? ORDER BY r.id LIMIT ?";
    private static final String CATALOG_REPORTS = """
        SELECT report.id, report.release_id, report.reason, report.created_at,
        """ + CATALOG_AUTHOR_COLUMNS
        + """
            FROM assistant_catalog_report report
            JOIN assistant_catalog_release r ON r.id = report.release_id AND r.workspace_id = report.workspace_id
            """
        + CATALOG_SOURCE
        + """
            WHERE report.account_id = ? AND EXISTS (
                SELECT 1 FROM assistant_workspace w WHERE w.id = ? AND w.personal_owner_id = report.account_id)
            """
        + CATALOG_LIVE_ORIGIN + "AND report.id > ? ORDER BY report.id LIMIT ?";
    private static final TypeReference<List<Point>> POINTS = new TypeReference<>() {
    };

    private static final RowMapper<Profile> PROFILE_ROW =
        (row, index) -> new Profile(row.getString("id"),
            row.getString("username"),
            new Preferences(row.getString("locale"), row.getString("theme")),
            Instant.ofEpochMilli(row.getLong("created_at")));
    private static final RowMapper<Project> PROJECT_ROW = (row, index) -> new Project(
        row.getString("id"), row.getString("name"), row.getString("description"),
        Instant.ofEpochMilli(row.getLong("created_at")),
        Instant.ofEpochMilli(row.getLong("updated_at")));
    private static final RowMapper<Conversation> CONVERSATION_ROW =
        (row, index) -> new Conversation(
            row.getString("id"), row.getString("title"), row.getBoolean("pinned"),
            row.getBoolean("archived"),
            Instant.ofEpochMilli(row.getLong("created_at")),
            Instant.ofEpochMilli(row.getLong("updated_at")), row.getString("project_id"));
    private static final RowMapper<Message> MESSAGE_ROW = (row, index) -> new Message(
        row.getString("id"), row.getString("conversation_id"), row.getString("parent_id"),
        row.getString("role"),
        content(row), Instant.ofEpochMilli(row.getLong("created_at")));
    private static final RowMapper<Run> RUN_ROW = (row, index) -> new Run(
        row.getString("id"), row.getString("conversation_id"), row.getString("prompt_message_id"),
        row.getString("response_message_id"), row.getString("state"), content(row),
        row.getString("model_id"),
        row.getString("model_version"), row.getString("fallback_from_model_id"),
        AssistantRunContext.read(row),
        row.getString("error_code"), Instant.ofEpochMilli(row.getLong("created_at")),
        Instant.ofEpochMilli(row.getLong("updated_at")), optionalTime(row, "finished_at"));
    private static final RowMapper<ProjectResource> RESOURCE_ROW =
        (row, index) -> new ProjectResource(row.getString("id"), row.getString("project_id"),
            row.getString("name"),
            content(row), Instant.ofEpochMilli(row.getLong("created_at")));
    private static final RowMapper<StoredMemorySetting> MEMORY_SETTING_ROW =
        (row, index) -> new StoredMemorySetting(row.getString("scope_id"),
            row.getString("scope_kind"),
            row.getString("project_id"), row.getBoolean("memory_enabled"),
            row.getBoolean("reference_history"), boundedText(row, "instructions"),
            row.getLong("memory_epoch"), row.getLong("history_epoch"),
            row.getLong("instructions_epoch"));
    private static final RowMapper<MemoryEntry> MEMORY_ENTRY_ROW =
        (row, index) -> new MemoryEntry(row.getString("id"), row.getString("scope_kind"),
            row.getString("project_id"),
            content(row), Instant.ofEpochMilli(row.getLong("created_at")));
    private static final RowMapper<Attachment> ATTACHMENT_ROW = (row, index) -> {
        String kind = row.getString("media_kind");
        String contentType = switch (kind) {
            case "WAVE" -> "audio/wav";
            case "PDF" -> MediaType.TEXT_PLAIN_VALUE;
            default -> MediaType.IMAGE_PNG_VALUE;
        };
        boolean image = "PNG".equals(kind) || "EDIT".equals(kind);
        Proof edit = AttachmentImageEditProof.read(row);
        var format =
            "WAVE".equals(kind) ? new AssistantAttachments.AudioInfo(row.getInt("sample_rate"),
                row.getInt("channel_count"), row.getInt("duration_millis")) : null;
        return new Attachment(row.getString("id"), row.getString("project_id"),
            contentType, row.getInt("size_bytes"),
            image ? row.getInt("width") : null, image ? row.getInt("height") : null, format,
            Instant.ofEpochMilli(row.getLong("created_at")), edit == null ? null : edit.origin(),
            edit);
    };
    private static final RowMapper<StoredVersion> CUSTOM_VERSION_ROW =
        (row, index) -> new StoredVersion(
            row.getString("id"), row.getString("assistant_id"), row.getInt("version_no"),
            row.getString("name"),
            boundedText(row, "instructions"), row.getString("tool_id"),
            Instant.ofEpochMilli(row.getLong("created_at")),
            row.getString("restored_from_version_id"));
    private static final RowMapper<VersionResource> VERSION_RESOURCE_ROW =
        (row, index) -> new VersionResource(
            row.getString("version_id"), row.getString("resource_id"));
    private static final RowMapper<OccurrenceRow> OCCURRENCE_ROW = (row,
        index) -> new OccurrenceRow(new Occurrence(
            row.getString("id"), row.getString("reminder_id"), row.getString("title"),
            row.getString("scheduled_local_at"),
            Instant.ofEpochMilli(row.getLong("planned_at")),
            Instant.ofEpochMilli(row.getLong("delivery_at")),
            Instant.ofEpochMilli(row.getLong("occurred_at")), row.getString("outcome"),
            optionalTime(row, "read_at")));
    private static final RowMapper<ArtifactExportRow<AssistantArtifacts.Summary>> ARTIFACT_ROW =
        (row, index) -> {
            String id = row.getString("id");
            var summary = new AssistantArtifacts.Summary(id, row.getString("project_id"),
                row.getString("source_message_id"),
                row.getString("format"), row.getString("head_version_id"), row.getInt("version_no"),
                row.getString("title"),
                row.getInt("size_bytes"), Instant.ofEpochMilli(row.getLong("created_at")),
                Instant.ofEpochMilli(row.getLong("updated_at")), null);
            return new ArtifactExportRow<>(id, id, row.getString("source_conversation_id"),
                AssistantArtifactImages.source(row), summary);
        };
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final AssistantDeletionJournal journal;

    /**
     * Creates an export service that shares the application's safe JSON encoding.
     *
     * @param jdbc product data access
     * @param mapper application JSON mapper
     * @param journal live revocations outside this database snapshot
     */
    public AssistantExport(JdbcTemplate jdbc, ObjectMapper mapper,
        AssistantDeletionJournal journal) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.journal = journal;
    }

    /**
     * Takes a repeatable-read snapshot for one export page before returning any bytes.
     *
     * @param principal authenticated consumer and selected workspace
     * @param cursor optional account- and workspace-bound continuation
     * @return a complete UTF-8 JSON document
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 20)
    public byte[] download(Principal principal, String cursor) {
        Scope scope = new Scope(principal.user().id(), principal.user().workspaceId());
        String readFence = journal.conversationReadFence(scope.accountId(), scope.workspaceId());
        Position position = Position.parse(cursor, scope);
        long now = System.currentTimeMillis();
        Profile profile = jdbc.query(PROFILE, PROFILE_ROW, scope.accountId(), scope.workspaceId(),
            principal.sessionId(), now).stream().findFirst()
            .orElseThrow(() -> new AssistantApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED",
                "The export session is unavailable."));

        var page = new Page(scope, position);
        var projects = page.read(Section.PROJECTS, PROJECTS, PROJECT_ROW);
        var conversations = page.read(Section.CONVERSATIONS, CONVERSATIONS, CONVERSATION_ROW);
        var messages = page.read(Section.MESSAGES, MESSAGES, MESSAGE_ROW);
        var runs = page.read(Section.RUNS, RUNS, RUN_ROW);
        var resources = page.read(Section.PROJECT_RESOURCES, RESOURCES, RESOURCE_ROW);
        var analyses = page.read(Section.ANALYSES, ANALYSES, (row, index) -> analysis(row));
        var settings = Stream.concat(
            page.read(Section.PERSONAL_MEMORY, PERSONAL_MEMORY_SETTINGS, MEMORY_SETTING_ROW)
                .stream(),
            page.read(Section.PROJECT_MEMORY, PROJECT_MEMORY_SETTINGS, MEMORY_SETTING_ROW).stream())
            .map(setting -> memorySetting(scope, setting)).toList();
        var memories = page.read(Section.MEMORY_ENTRIES, MEMORY_ENTRIES, MEMORY_ENTRY_ROW);
        var attachments = page.read(Section.ATTACHMENTS, ATTACHMENTS, ATTACHMENT_ROW);
        var assistants = page.read(Section.CUSTOM_ASSISTANTS, CUSTOM_ASSISTANTS,
            (row, index) -> customHead(scope, row));
        var versions = customVersions(scope,
            page.read(Section.CUSTOM_VERSIONS, CUSTOM_VERSIONS, CUSTOM_VERSION_ROW));
        var reminders =
            page.read(Section.REMINDERS, REMINDERS, (row, index) -> reminder(scope, row));
        var history = page.read(Section.REMINDER_HISTORY, REMINDER_HISTORY, OCCURRENCE_ROW);
        var artifacts = page.read(Section.ARTIFACTS, ARTIFACTS, ARTIFACT_ROW);
        var artifactVersions =
            page.read(Section.ARTIFACT_VERSIONS, ARTIFACT_VERSIONS,
                (row, index) -> artifactVersion(row));
        var catalogAuthored =
            page.read(Section.CATALOG_AUTHORED_RELEASES, CATALOG_AUTHORED_RELEASES,
                (row, index) -> catalogRelease(row));
        var catalogReports = page.read(Section.CATALOG_REPORTS, CATALOG_REPORTS,
            (row, index) -> catalogReport(row));
        var document = new Document(1, Instant.ofEpochMilli(now), scope.workspaceId(), profile,
            projects, conversations, messages, runs, resources, analyses,
            settings.stream().map(SettingProjection::setting).toList(), memories, attachments,
            assistants.stream().map(AssistantProjection::summary).toList(), versions,
            reminders.stream().map(ReminderProjection::reminder).toList(),
            history.stream().map(OccurrenceRow::occurrence).toList(),
            artifacts.stream().map(ArtifactExportRow::value).toList(),
            artifactVersions.stream().map(ArtifactExportRow::value).toList(),
            catalogAuthored.stream().map(CatalogRow::value).toList(),
            catalogReports.stream().map(CatalogRow::value).toList(),
            page.next);
        byte[] body = encode(document);
        synchronized (journal) {
            verifySnapshot(scope, document,
                settings.stream().map(SettingProjection::policy).toList(),
                assistants, reminders,
                Stream.<ArtifactExportRow<?>>concat(artifacts.stream(), artifactVersions.stream())
                    .toList(),
                readFence);
            verifyCatalogSnapshot(Stream.<CatalogRow<?>>concat(catalogAuthored.stream(),
                catalogReports.stream()).toList());
        }
        return body;
    }

    private CatalogRow<CatalogRelease> catalogRelease(ResultSet row) throws SQLException {
        String id = row.getString("id");
        var origin = catalogOrigin(row);
        boolean retired =
            journal.catalogReleaseRetired(origin.accountId(), origin.workspaceId(), id);
        String state = row.getString("state");
        if (retired && ("pending".equals(state) || "published".equals(state))) {
            state = "withdrawn";
        }
        var receipt = new CatalogRelease(id, row.getString("entry_id"), row.getString("kind"),
            state, row.getInt("version_number"), row.getString("title"), row.getString("summary"),
            Instant.ofEpochMilli(row.getLong("submitted_at")), optionalTime(row, "decided_at"));
        return new CatalogRow<>(id, receipt, origin, retired);
    }

    private static CatalogRow<Report> catalogReport(ResultSet row) throws SQLException {
        String id = row.getString("id");
        var receipt = new Report(id, row.getString("release_id"), row.getString("reason"),
            Instant.ofEpochMilli(row.getLong("created_at")));
        return new CatalogRow<>(id, receipt, catalogOrigin(row), null);
    }

    private static CatalogOrigin catalogOrigin(ResultSet row) throws SQLException {
        return new CatalogOrigin(row.getString("author_account_id"),
            row.getString("author_workspace_id"), row.getString("author_membership_revision"),
            row.getString("source_assistant_id"));
    }

    private ArtifactExportRow<AssistantArtifacts.Version> artifactVersion(ResultSet row)
        throws SQLException {
        String id = row.getString("id");
        String artifactId = row.getString("artifact_id");
        var summary =
            new AssistantArtifacts.VersionSummary(id, artifactId, row.getInt("version_no"),
                row.getString("title"), row.getInt("size_bytes"),
                Instant.ofEpochMilli(row.getLong("created_at")),
                row.getString("restored_from_version_id"));
        var source = AssistantArtifactImages.source(row);
        var version = AssistantArtifacts.Version.fromStorage(mapper, summary,
            row.getString("format"), content(row), source);
        return new ArtifactExportRow<>(id, artifactId, row.getString("source_conversation_id"),
            source, version);
    }

    private SettingProjection memorySetting(Scope scope, StoredMemorySetting stored) {
        var cutoff =
            journal.memoryRevocations(scope.accountId(), scope.workspaceId(), stored.projectId());
        boolean personal = stored.projectId() == null;
        String instructions =
            stored.instructionsEpoch() > cutoff.instructions() ? stored.instructions() : "";
        var setting = new MemorySetting(stored.scope(), stored.projectId(),
            stored.enabled() && stored.memoryEpoch() > cutoff.memory(),
            personal ? stored.referenceHistory() && stored.historyEpoch() > cutoff.history() : null,
            personal ? instructions : null);
        return new SettingProjection(setting, new ExportMemoryPolicy(stored.projectId(), cutoff));
    }

    private AssistantProjection customHead(Scope scope, ResultSet row) throws SQLException {
        String id = row.getString("id");
        long cutoff = journal.customAssistantCutoff(scope.accountId(), scope.workspaceId(), id);
        String status = row.getLong("head_epoch") > cutoff ? "active" : "requires_review";
        var summary = new AssistantCustomAssistants.Summary(id, row.getString("name"),
            row.getString("project_id"),
            row.getString("head_version_id"), row.getInt("version_no"), status,
            Instant.ofEpochMilli(row.getLong("created_at")),
            Instant.ofEpochMilli(row.getLong("updated_at")));
        return new AssistantProjection(summary, cutoff);
    }

    private List<Version> customVersions(Scope scope, List<StoredVersion> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        var bindings = new ArrayList<Object>(rows.size() + 2);
        bindings.add(scope.accountId());
        bindings.add(scope.workspaceId());
        rows.forEach(row -> bindings.add(row.id()));
        String placeholders = String.join(", ", Collections.nCopies(rows.size(), "?"));
        var resources = jdbc
            .query(CUSTOM_RESOURCES.formatted(placeholders), VERSION_RESOURCE_ROW,
                bindings.toArray())
            .stream().collect(Collectors.groupingBy(VersionResource::versionId,
                Collectors.mapping(VersionResource::resourceId, Collectors.toList())));
        return rows.stream()
            .map(row -> new Version(row.id(), row.assistantId(), row.number(), row.name(),
                row.instructions(), resources.getOrDefault(row.id(), List.of()),
                row.toolId() == null ? List.of() : List.of(row.toolId()), row.createdAt(),
                row.restoredFromVersionId()))
            .toList();
    }

    private ReminderProjection reminder(Scope scope, ResultSet row) throws SQLException {
        String id = row.getString("id");
        long cutoff = journal.reminderPauseCutoff(scope.accountId(), scope.workspaceId(), id);
        String status = row.getString("status");
        if ("active".equals(status) && row.getLong("config_epoch") <= cutoff) {
            status = "paused";
        }
        String quietStart = row.getString("quiet_start");
        QuietHours quietHours =
            quietStart == null ? null : new QuietHours(quietStart, row.getString("quiet_end"));
        var reminder = new Reminder(id, row.getString("title"), row.getString("starts_at_local"),
            row.getString("time_zone"), row.getString("cadence"), quietHours, status, null, null,
            Instant.ofEpochMilli(row.getLong("created_at")),
            Instant.ofEpochMilli(row.getLong("updated_at")));
        return new ReminderProjection(reminder, cutoff);
    }

    private Analysis analysis(ResultSet row) throws SQLException {
        String chart = row.getString("series_json");
        String artifact = row.getString("artifact_csv");
        if (chart == null || artifact == null) {
            throw tooLarge();
        }
        List<Point> points;
        try {
            points = mapper.readValue(chart, POINTS);
        } catch (JsonProcessingException failure) {
            throw unavailable();
        }
        if (points == null || points.size() > 32) {
            throw unavailable();
        }
        var summary = new Summary(row.getString("id"), row.getString("project_id"),
            row.getString("resource_id"), row.getString("value_column"), row.getInt("row_count"),
            row.getInt("value_count"), row.getString("min_value"), row.getString("max_value"),
            row.getString("sum_value"), row.getString("mean_value"), points,
            Instant.ofEpochMilli(row.getLong("created_at")), row.getLong("duration_millis"),
            row.getString("algorithm_version"));
        return new Analysis(summary, artifact);
    }

    private void verifySnapshot(Scope scope, Document document, List<ExportMemoryPolicy> policies,
        List<AssistantProjection> assistantProjections,
        List<ReminderProjection> reminderProjections, List<ArtifactExportRow<?>> artifactRows,
        String readFence) {
        var conversations = new HashSet<String>();
        document.conversations().forEach(row -> conversations.add(row.id()));
        document.messages().forEach(row -> conversations.add(row.conversationId()));
        document.runs().forEach(row -> conversations.add(row.conversationId()));
        var resources = new HashSet<String>();
        document.projectResources().forEach(row -> resources.add(row.id()));
        document.analyses().forEach(row -> resources.add(row.summary().resourceId()));
        document.customAssistantVersions().forEach(row -> resources.addAll(row.resourceIds()));
        var memories = document.memoryEntries().stream().map(MemoryEntry::id).toList();
        var attachments = Stream.concat(document.attachments().stream().map(Attachment::id),
            artifactRows.stream().map(ArtifactExportRow::imageSource)
                .filter(Objects::nonNull).map(AssistantArtifactImages.Source::id))
            .distinct().toList();
        var assistants = new HashSet<String>();
        document.customAssistants().forEach(row -> assistants.add(row.id()));
        document.customAssistantVersions().forEach(row -> assistants.add(row.assistantId()));
        var reminders = new HashSet<String>();
        document.reminders().forEach(row -> reminders.add(row.id()));
        document.reminderOccurrences().forEach(row -> reminders.add(row.reminderId()));
        String account = scope.accountId();
        String workspace = scope.workspaceId();
        synchronized (journal) {
            if (!Objects.equals(readFence, journal.conversationReadFence(account, workspace))) {
                throw new AssistantApiException(HttpStatus.CONFLICT, "CONVERSATION_LOCK_CHANGED",
                    "A conversation lock changed. Restart the export.");
            }
            if (!journal.allowsExportSnapshot(account, workspace, conversations, resources,
                memories, attachments,
                policies)
                || assistants.stream()
                    .anyMatch(id -> journal.blocksCustomAssistant(account, workspace, id))
                || reminders.stream().anyMatch(id -> journal.blocksReminder(account, workspace, id))
                || document.attachments().stream().anyMatch(row -> row.editProof() != null
                    && !row.editProof().allows(journal, account, workspace))
                || artifactRows.stream().anyMatch(row -> journal.blocksArtifact(account, workspace,
                    row.artifactId(), row.conversationId())
                    || row.imageSource() != null
                        && !row.imageSource().allows(journal, account, workspace))
                || assistantProjections.stream()
                    .anyMatch(row -> row.cutoff() != journal.customAssistantCutoff(account,
                        workspace, row.id()))
                || reminderProjections.stream()
                    .anyMatch(row -> row.cutoff() != journal.reminderPauseCutoff(account,
                        workspace, row.id()))) {
                throw unavailable();
            }
        }
    }

    private void verifyCatalogSnapshot(List<CatalogRow<?>> rows) {
        for (var row : rows) {
            CatalogOrigin origin = row.origin();
            String owner = origin.accountId();
            String workspace = origin.workspaceId();
            if (journal.blocksCollaborationParticipant(owner)
                || journal.blocksWorkspaceMembership(owner, workspace, origin.membershipRevision())
                || origin.assistantId() != null
                    && journal.blocksCustomAssistant(owner, workspace, origin.assistantId())
                || row.retired() != null
                    && row.retired() != journal.catalogReleaseRetired(owner, workspace, row.id())) {
                throw unavailable();
            }
        }
    }

    private <T extends Entry> Slice<T> query(String sql, RowMapper<T> converter, Scope scope,
        String after,
        int remaining) {
        List<T> rows =
            jdbc.query(sql, (row, index) -> index < remaining ? converter.mapRow(row, index) : null,
                scope.accountId(), scope.workspaceId(), after, remaining + 1);
        boolean hasMore = rows.size() > remaining;
        return new Slice<>(hasMore ? rows.subList(0, remaining) : rows, hasMore);
    }

    private byte[] encode(Document document) {
        BoundedOutput output = new BoundedOutput();
        try {
            mapper.writeValue(output, document);
            return output.bytes();
        } catch (IOException failure) {
            if (output.exceeded()) {
                throw tooLarge();
            }
            throw new AssistantApiException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "The export could not be encoded.");
        }
    }

    private static String content(ResultSet row) throws SQLException {
        return boundedText(row, "content");
    }

    private static String boundedText(ResultSet row, String name) throws SQLException {
        String value = row.getString(name);
        if (value == null) {
            throw tooLarge();
        }
        return value;
    }

    private static Instant optionalTime(ResultSet row, String column) throws SQLException {
        long epoch = row.getLong(column);
        return row.wasNull() ? null : Instant.ofEpochMilli(epoch);
    }

    private static AssistantApiException invalidCursor() {
        return new AssistantApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
            "The export cursor is invalid for this account and workspace.");
    }

    private static AssistantApiException tooLarge() {
        return new AssistantApiException(HttpStatus.PAYLOAD_TOO_LARGE, "CAPABILITY_UNAVAILABLE",
            "A record exceeds the limits of this export.");
    }

    private static AssistantApiException unavailable() {
        return new AssistantApiException(HttpStatus.SERVICE_UNAVAILABLE, "CAPABILITY_UNAVAILABLE",
            "The export snapshot is unavailable. Try the download again.");
    }

    private enum Section {
        PROJECTS,
        CONVERSATIONS,
        MESSAGES,
        RUNS,
        PROJECT_RESOURCES,
        ANALYSES,
        PERSONAL_MEMORY,
        PROJECT_MEMORY,
        MEMORY_ENTRIES,
        ATTACHMENTS,
        CUSTOM_ASSISTANTS,
        CUSTOM_VERSIONS,
        REMINDERS,
        REMINDER_HISTORY,
        ARTIFACTS,
        ARTIFACT_VERSIONS,
        CATALOG_AUTHORED_RELEASES,
        CATALOG_REPORTS
    }

    private record Scope(String accountId, String workspaceId) {
    }

    private record Position(Section section, String after) {

        private static Position parse(String cursor, Scope scope) {
            if (cursor == null) {
                return new Position(Section.PROJECTS, "");
            }
            if (cursor.length() > 180 || cursor.isEmpty()) {
                throw invalidCursor();
            }
            try {
                String decoded =
                    new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.US_ASCII);
                String[] parts = decoded.split(":", -1);
                if (parts.length == 5 && "1".equals(parts[0]) && scope.accountId().equals(parts[1])
                    && scope.workspaceId().equals(parts[2])
                    && (parts[4].isEmpty()
                        || UUID.fromString(parts[4]).toString().equals(parts[4]))) {
                    return new Position(Section.valueOf(parts[3]), parts[4]);
                }
            } catch (IllegalArgumentException ignored) {
                throw invalidCursor();
            }
            throw invalidCursor();
        }

        private String after(Section requested) {
            return section == requested ? after : "";
        }

        private String encode(Scope scope) {
            String value = "1:" + scope.accountId() + ":" + scope.workspaceId() + ":"
                + section.name() + ":" + after;
            return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.US_ASCII));
        }
    }

    private record Slice<T extends Entry>(List<T> items, boolean hasMore) {

        private String lastId() {
            return items.isEmpty() ? "" : items.get(items.size() - 1).id();
        }
    }

    private final class Page {

        private final Scope scope;
        private final Position position;
        private int remaining = PAGE_SIZE;
        private String next;

        private Page(Scope scope, Position position) {
            this.scope = scope;
            this.position = position;
        }

        private <T extends Entry> List<T> read(Section section, String sql, RowMapper<T> row) {
            if (next != null || position.section().compareTo(section) > 0) {
                return List.of();
            }
            Slice<T> slice = query(sql, row, scope, position.after(section), remaining);
            remaining -= slice.items().size();
            if (slice.hasMore()) {
                next = new Position(section, slice.lastId()).encode(scope);
            }
            return slice.items();
        }
    }

    private interface Entry {

        String id();
    }

    private record Profile(String id, String username, Preferences preferences, Instant createdAt) {
    }

    private record CatalogOrigin(String accountId, String workspaceId, String membershipRevision,
        String assistantId) {
    }

    private record CatalogRow<T>(String id, T value, CatalogOrigin origin, Boolean retired)
        implements
            Entry {
    }

    private record Project(String id, String name, String description, Instant createdAt,
        Instant updatedAt) implements Entry {
    }

    private record ProjectResource(String id, String projectId, String name, String content,
        Instant createdAt) implements Entry {
    }

    private record Analysis(@JsonUnwrapped Summary summary, String artifactCsv) implements Entry {

        @Override
        public String id() {
            return summary.id();
        }
    }

    private record StoredMemorySetting(String id, String scope, String projectId, boolean enabled,
        boolean referenceHistory, String instructions, long memoryEpoch, long historyEpoch,
        long instructionsEpoch) implements Entry {

        @Override
        public String toString() {
            return "StoredMemorySetting[protected]";
        }
    }

    private record SettingProjection(MemorySetting setting, ExportMemoryPolicy policy) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record MemorySetting(String scope, String projectId, boolean enabled,
        Boolean referenceHistory, String instructions) {

        @Override
        public String toString() {
            return "MemorySetting[protected]";
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record MemoryEntry(String id, String scope, String projectId, String content,
        Instant createdAt) implements Entry {

        @Override
        public String toString() {
            return "MemoryEntry[protected]";
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record Attachment(String id, String projectId, String contentType, int size,
        Integer width,
        Integer height, AssistantAttachments.AudioInfo audio, Instant createdAt, Origin localEdit,
        @JsonIgnore Proof editProof) implements Entry {
    }

    private record AssistantProjection(AssistantCustomAssistants.Summary summary,
        long cutoff) implements Entry {

        @Override
        public String id() {
            return summary.id();
        }
    }

    private record StoredVersion(String id, String assistantId, int number, String name,
        String instructions,
        String toolId, Instant createdAt, String restoredFromVersionId) implements Entry {

        @Override
        public String toString() {
            return "StoredVersion[protected]";
        }
    }

    private record VersionResource(String versionId, String resourceId) {
    }

    private record ReminderProjection(Reminder reminder, long cutoff) implements Entry {

        @Override
        public String id() {
            return reminder.id();
        }
    }

    private record OccurrenceRow(Occurrence occurrence) implements Entry {

        @Override
        public String id() {
            return occurrence.id();
        }
    }

    private record ArtifactExportRow<T>(String id, String artifactId, String conversationId,
        AssistantArtifactImages.Source imageSource,
        T value) implements Entry {
    }

    private record Conversation(String id, String title, boolean pinned, boolean archived,
        Instant createdAt,
        Instant updatedAt,
        @JsonInclude(JsonInclude.Include.NON_NULL) String projectId) implements Entry {
    }

    private record Message(String id, String conversationId,
        @JsonInclude(JsonInclude.Include.ALWAYS) String parentId,
        String role, String content, Instant createdAt) implements Entry {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record Run(String id, String conversationId, String promptMessageId,
        String responseMessageId,
        String state, String content, String modelId, String modelVersion,
        String fallbackFromModelId,
        AssistantRunContext context, String errorCode, Instant createdAt, Instant updatedAt,
        Instant finishedAt) implements Entry {
    }

    private record Document(int schemaVersion, Instant exportedAt, String workspaceId,
        Profile account,
        List<Project> projects, List<Conversation> conversations, List<Message> messages,
        List<Run> runs, List<ProjectResource> projectResources, List<Analysis> analyses,
        List<MemorySetting> memorySettings, List<MemoryEntry> memoryEntries,
        List<Attachment> attachments, List<AssistantCustomAssistants.Summary> customAssistants,
        List<Version> customAssistantVersions, List<Reminder> reminders,
        List<Occurrence> reminderOccurrences,
        List<AssistantArtifacts.Summary> privateArtifacts,
        List<AssistantArtifacts.Version> privateArtifactVersions,
        List<CatalogRelease> catalogAuthoredReleases, List<Report> catalogReports,
        @JsonInclude(JsonInclude.Include.ALWAYS) String nextCursor) {
    }

    private static final class BoundedOutput extends OutputStream {

        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private boolean exceeded;

        @Override
        public void write(int value) throws IOException {
            ensure(1);
            output.write(value);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            ensure(length);
            output.write(bytes, offset, length);
        }

        private void ensure(int length) throws IOException {
            if (length > MAX_BYTES - output.size()) {
                exceeded = true;
                throw new IOException("Export byte budget exceeded.");
            }
        }

        private boolean exceeded() {
            return exceeded;
        }

        private byte[] bytes() {
            return output.toByteArray();
        }
    }
}

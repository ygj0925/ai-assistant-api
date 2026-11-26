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

package top.continew.admin.assistant.project;

import com.fasterxml.jackson.annotation.JsonInclude;
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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Stores bounded plain-text materials in an owned private project.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
@Service
@ConditionalOnProperty(name = "assistant.enabled", havingValue = "true")
public class AssistantProjectResources {

    private static final int MAX_NAME_POINTS = 120;
    private static final int MAX_CONTENT_BYTES = 16 * 1024;
    private static final int MAX_QUERY_POINTS = 100;
    private static final int MAX_ACTIVE_PER_PROJECT = 64;
    private static final int DEFAULT_LIMIT = 25;
    private static final int MAX_LIMIT = 100;
    private static final String SOURCE = """
         FROM assistant_project_resource
         WHERE workspace_id = ? AND account_id = ? AND project_id = ? AND deleted_at IS NULL
        """;
    private static final RowMapper<ResourceSummary> SUMMARY = (row, index) -> new ResourceSummary(
        row.getString("id"), row.getString("name"),
        Instant.ofEpochMilli(row.getLong("created_at")));
    private static final RowMapper<Resource> RESOURCE = (row, index) -> new Resource(
        row.getString("id"), row.getString("name"), row.getString("content"),
        Instant.ofEpochMilli(row.getLong("created_at")));

    private final JdbcTemplate jdbc;
    private final AssistantAccounts accounts;
    private final AssistantProjects projects;
    private final AssistantDeletionJournal journal;
    private final AssistantErasure erasure;
    private final boolean enabled;

    /**
     * Connects text materials to private projects and the bound deletion journal.
     *
     * @param jdbc scoped product data access
     * @param accounts account lifecycle
     * @param projects private project ownership
     * @param journal durable deletion readiness
     * @param erasure material revocation
     * @param enabled whether project text materials are configured
     */
    public AssistantProjectResources(JdbcTemplate jdbc, AssistantAccounts accounts,
        AssistantProjects projects, AssistantDeletionJournal journal, AssistantErasure erasure,
        @Value("${assistant.project-resources.enabled:false}") boolean enabled) {
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.projects = projects;
        this.journal = journal;
        this.erasure = erasure;
        this.enabled = enabled;
    }

    /**
     * Saves one text material after validation and the per-project quota check.
     *
     * @param owner authenticated owner in the selected workspace
     * @param projectId owned project
     * @param name display name
     * @param content original UTF-8 text
     * @return created material
     */
    @Transactional
    public Resource create(User owner, String projectId, String name, String content) {
        requireReady();
        if (name == null || !bounded(name, MAX_NAME_POINTS, false) || name.isBlank()) {
            throw invalid("Material name must contain between 1 and 120 text characters.");
        }
        String title = name.strip();
        if (content == null || content.length() > MAX_CONTENT_BYTES || content.isBlank()
            || !bounded(content, MAX_CONTENT_BYTES, true)
            || content.getBytes(StandardCharsets.UTF_8).length > MAX_CONTENT_BYTES) {
            throw invalid("Material must contain valid plain text of at most 16 KiB.");
        }
        accounts.lockActive(owner.id());
        projects.requireOwned(owner, projectId);
        Integer current = jdbc.queryForObject("SELECT COUNT(*)" + SOURCE, Integer.class,
            owner.workspaceId(), owner.id(), projectId);
        if (current != null && current >= MAX_ACTIVE_PER_PROJECT) {
            throw new AssistantApiException(HttpStatus.CONFLICT, "CONFLICT",
                "The project already contains 64 materials.");
        }
        String id = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        jdbc.update(
            """
                INSERT INTO assistant_project_resource
                    (id, workspace_id, account_id, project_id, name, content, search_name, search_content, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
            id, owner.workspaceId(), owner.id(), projectId, title, content, normalize(title),
            normalize(content), now);
        return new Resource(id, title, content, Instant.ofEpochMilli(now));
    }

    /**
     * Searches names and text with a literal, normalized substring and lists metadata only.
     *
     * @param owner authenticated owner in the selected workspace
     * @param projectId owned project
     * @param query optional search substring
     * @param limit optional page size
     * @param cursor optional continuation for this project and query
     * @return authorized page without material bodies
     */
    public ResourcePage list(User owner, String projectId, String query, Integer limit,
        String cursor) {
        requireReady();
        projects.requireOwned(owner, projectId);
        int size = limit == null ? DEFAULT_LIMIT : limit;
        if (size < 1 || size > MAX_LIMIT) {
            throw invalid("Material page size must be between 1 and 100.");
        }
        if (query != null && !bounded(query, MAX_QUERY_POINTS, false)) {
            throw invalid("Material search is limited to 100 text characters.");
        }
        String normalized = query == null ? "" : normalize(query).strip();
        String fingerprint = digestQuery(normalized);
        var sql = new StringBuilder("SELECT id, name, created_at").append(SOURCE);
        var args = new ArrayList<Object>(List.of(owner.workspaceId(), owner.id(), projectId));
        if (!normalized.isEmpty()) {
            String escaped = "%" + normalized.replace("!", "!!").replace("%", "!%")
                .replace("_", "!_") + "%";
            sql.append(" AND (search_name LIKE ? ESCAPE '!' OR search_content LIKE ? ESCAPE '!')");
            args.add(escaped);
            args.add(escaped);
        }
        if (cursor != null) {
            Cursor after = Cursor.decode(cursor, owner, projectId, fingerprint);
            sql.append(" AND (created_at < ? OR (created_at = ? AND id < ?))");
            args.add(after.createdAt());
            args.add(after.createdAt());
            args.add(after.id());
        }
        sql.append(" ORDER BY created_at DESC, id DESC LIMIT ?");
        args.add(size + 1);
        List<ResourceSummary> found = jdbc.query(sql.toString(), SUMMARY, args.toArray());
        requireReady();
        List<ResourceSummary> page = found.subList(0, Math.min(found.size(), size));
        String next =
            found.size() > size ? Cursor.encode(page.get(page.size() - 1), owner, projectId,
                fingerprint) : null;
        return new ResourcePage(page.stream().filter(item -> !erasure.blocksProjectResource(owner,
            item.id())).toList(), next);
    }

    /**
     * Loads the original text of one authorized, live project material.
     *
     * @param owner authenticated owner in the selected workspace
     * @param projectId owned project
     * @param resourceId material identifier
     * @return authorized material
     */
    public Resource get(User owner, String projectId, String resourceId) {
        requireReady();
        projects.requireOwned(owner, projectId);
        requireId(resourceId);
        return jdbc.query("SELECT id, name, content, created_at" + SOURCE + " AND id = ?", RESOURCE,
            owner.workspaceId(), owner.id(), projectId, resourceId).stream()
            .filter(item -> !erasure.blocksProjectResource(owner, item.id())).findFirst()
            .orElseThrow(AssistantProjectResources::notFound);
    }

    /**
     * Revokes an owned material and erases its body in the current transaction.
     *
     * @param owner authenticated owner in the selected workspace
     * @param projectId owned project
     * @param resourceId material identifier
     */
    @Transactional
    public void delete(User owner, String projectId, String resourceId) {
        requireReady();
        requireId(resourceId);
        accounts.lockActive(owner.id());
        projects.requireOwned(owner, projectId);
        erasure.deleteProjectResource(owner, projectId, resourceId);
    }

    private void requireReady() {
        if (!enabled || !journal.enabled()) {
            throw new AssistantApiException(HttpStatus.FORBIDDEN, "CAPABILITY_UNAVAILABLE",
                "Project materials are unavailable.");
        }
        if (!journal.acceptsErasure()) {
            throw new AssistantApiException(HttpStatus.SERVICE_UNAVAILABLE,
                "CAPABILITY_UNAVAILABLE",
                "Project material revocation is temporarily unavailable.");
        }
    }

    private static boolean bounded(String value, int maximum, boolean multiline) {
        return value.length() <= maximum * 2
            && value.codePointCount(0, value.length()) <= maximum
            && StandardCharsets.UTF_8.newEncoder().canEncode(value)
            && value.codePoints().noneMatch(point -> Character.isISOControl(point)
                && (!multiline || (point != '\n' && point != '\r' && point != '\t')));
    }

    private static String normalize(String input) {
        return Normalizer.normalize(input, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    }

    private static String digestQuery(String input) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable.", failure);
        }
    }

    private static void requireId(String id) {
        if (!validId(id)) {
            throw invalid("Invalid material identifier.");
        }
    }

    private static boolean validId(String id) {
        try {
            return id != null && id.length() == 36 && UUID.fromString(id).toString().equals(id);
        } catch (IllegalArgumentException failure) {
            return false;
        }
    }

    private static AssistantApiException invalid(String message) {
        return new AssistantApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message);
    }

    private static AssistantApiException notFound() {
        return new AssistantApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Material not found.");
    }

    /**
     * Authorized material stored independently of the conversation history.
     *
     * @param id material identifier
     * @param name display name
     * @param content original plain text
     * @param createdAt creation instant
     */
    public record Resource(String id, String name, String content, Instant createdAt) {

        @Override
        public String toString() {
            return "Resource[protected]";
        }
    }

    /**
     * Metadata of a matching material.
     *
     * @param id material identifier
     * @param name display name
     * @param createdAt creation instant
     */
    public record ResourceSummary(String id, String name, Instant createdAt) {
    }

    /**
     * Search or browsing results for one private project.
     *
     * @param items visible matching metadata
     * @param nextCursor continuation or null when there are no further results
     */
    public record ResourcePage(List<ResourceSummary> items,
        @JsonInclude(JsonInclude.Include.ALWAYS) String nextCursor) {
    }

    private record Cursor(long createdAt, String id) {

        private static Cursor decode(String token, User owner, String projectId, String queryHash) {
            if (token.length() > 320) {
                throw invalid("Invalid material cursor.");
            }
            try {
                String clear =
                    new String(Base64.getUrlDecoder().decode(token), StandardCharsets.US_ASCII);
                String[] fields = clear.split(":", -1);
                if (fields.length == 7 && "1".equals(fields[0])
                    && owner.workspaceId().equals(fields[1]) && owner.id().equals(fields[2])
                    && projectId.equals(fields[3]) && queryHash.equals(fields[4])
                    && validId(fields[6])) {
                    long time = Long.parseLong(fields[5]);
                    if (time > 0) {
                        return new Cursor(time, fields[6]);
                    }
                }
            } catch (IllegalArgumentException failure) {
                throw invalid("Invalid material cursor.");
            }
            throw invalid("Invalid material cursor.");
        }

        private static String encode(ResourceSummary item, User owner, String projectId,
            String queryHash) {
            String clear = "1:" + owner.workspaceId() + ":" + owner.id() + ":" + projectId
                + ":" + queryHash + ":" + item.createdAt().toEpochMilli() + ":" + item.id();
            return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(clear.getBytes(StandardCharsets.US_ASCII));
        }
    }
}

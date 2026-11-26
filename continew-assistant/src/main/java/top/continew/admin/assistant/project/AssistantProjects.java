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
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.continew.admin.assistant.account.AssistantAccounts;
import top.continew.admin.assistant.account.AssistantAccounts.User;
import top.continew.admin.assistant.account.AssistantApiException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * Manages private projects owned by one consumer within a workspace.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
@Service
@ConditionalOnProperty(name = "assistant.enabled", havingValue = "true")
public class AssistantProjects {

    private static final int MAX_NAME_POINTS = 120;
    private static final int MAX_DESCRIPTION_POINTS = 4000;
    private static final int DEFAULT_LIMIT = 25;
    private static final int MAX_LIMIT = 100;
    private static final String SCOPE =
        " WHERE workspace_id = ? AND account_id = ? AND deleted_at IS NULL";
    private static final String SELECT =
        "SELECT id, name, description, created_at, updated_at FROM assistant_project";
    private static final String ORDER = " ORDER BY created_at DESC, id DESC LIMIT ?";
    private static final RowMapper<Project> PROJECT_ROW =
        (row, index) -> new Project(row.getString("id"),
            row.getString("name"), row.getString("description"),
            Instant.ofEpochMilli(row.getLong("created_at")),
            Instant.ofEpochMilli(row.getLong("updated_at")));

    private final JdbcTemplate jdbc;
    private final AssistantAccounts accounts;

    /**
     * Creates a project catalog with the consumer account lifecycle lock.
     *
     * @param jdbc product data access
     * @param accounts account lifecycle
     */
    public AssistantProjects(JdbcTemplate jdbc, AssistantAccounts accounts) {
        this.jdbc = jdbc;
        this.accounts = accounts;
    }

    /**
     * Creates a private project in the consumer's selected workspace.
     *
     * @param owner authenticated workspace owner of this project
     * @param input initial project metadata
     * @return created project
     */
    @Transactional
    public Project create(User owner, ProjectWrite input) {
        if (input == null || input.name() == null) {
            throw invalid("A project name is required.");
        }
        String name = name(input.name());
        String description = input.description() == null ? "" : description(input.description());
        accounts.lockActive(owner.id());
        String id = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        jdbc.update("""
            INSERT INTO assistant_project
                (id, workspace_id, account_id, name, description, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """, id, owner.workspaceId(), owner.id(), name, description, now, now);
        Instant time = Instant.ofEpochMilli(now);
        return new Project(id, name, description, time, time);
    }

    /**
     * Lists projects with deterministic, scope-bound pagination.
     *
     * @param owner authenticated consumer in the selected workspace
     * @param limit optional page limit
     * @param cursor optional continuation from the same scope
     * @return a page of visible projects
     */
    public ProjectPage list(User owner, Integer limit, String cursor) {
        int size = limit == null ? DEFAULT_LIMIT : limit;
        if (size < 1 || size > MAX_LIMIT) {
            throw invalid("Project page size must be between 1 and 100.");
        }
        List<Project> found;
        if (cursor == null) {
            found = jdbc.query(SELECT + SCOPE + ORDER, PROJECT_ROW, owner.workspaceId(), owner.id(),
                size + 1);
        } else {
            Cursor after = Cursor.decode(cursor, owner);
            found =
                jdbc.query(SELECT + SCOPE + " AND (created_at, id) < (?, ?)" + ORDER, PROJECT_ROW,
                    owner.workspaceId(), owner.id(), after.createdAt(), after.id(), size + 1);
        }
        if (found.size() <= size) {
            return new ProjectPage(found, null);
        }
        return new ProjectPage(found.subList(0, size), Cursor.encode(found.get(size - 1), owner));
    }

    /**
     * Reads one private project.
     *
     * @param owner authenticated project scope
     * @param id project identifier
     * @return live project metadata
     */
    public Project get(User owner, String id) {
        requireId(id);
        return jdbc
            .query(SELECT + SCOPE + " AND id = ?", PROJECT_ROW, owner.workspaceId(), owner.id(), id)
            .stream().findFirst().orElseThrow(AssistantProjects::notFound);
    }

    /**
     * Verifies a referenced project without loading its description.
     *
     * @param owner authenticated project scope
     * @param id referenced project identifier
     */
    public void requireOwned(User owner, String id) {
        requireId(id);
        if (jdbc
            .queryForList("SELECT id FROM assistant_project" + SCOPE + " AND id = ?", String.class,
                owner.workspaceId(), owner.id(), id)
            .isEmpty()) {
            throw notFound();
        }
    }

    /**
     * Updates supplied project metadata.
     *
     * @param owner authenticated project scope
     * @param id project identifier
     * @param input updated name or description
     * @return current metadata
     */
    @Transactional
    public Project update(User owner, String id, ProjectWrite input) {
        if (input == null) {
            throw invalid("A project update body is required.");
        }
        accounts.lockActive(owner.id());
        Project project = get(owner, id);
        String name = input.name() == null ? project.name() : name(input.name());
        String description =
            input.description() == null ? project.description() : description(input.description());
        if (project.name().equals(name) && project.description().equals(description)) {
            return project;
        }
        long updatedAt = Math.max(System.currentTimeMillis(), project.updatedAt().toEpochMilli());
        jdbc.update(
            "UPDATE assistant_project SET name = ?, description = ?, updated_at = ?" + SCOPE
                + " AND id = ?",
            name, description, updatedAt, owner.workspaceId(), owner.id(), id);
        return new Project(id, name, description, project.createdAt(),
            Instant.ofEpochMilli(updatedAt));
    }

    private static String name(String value) {
        String trimmed = value.strip();
        if (trimmed.isEmpty() || !bounded(trimmed, MAX_NAME_POINTS)) {
            throw invalid("Project name must contain between 1 and 120 characters.");
        }
        return trimmed;
    }

    private static String description(String value) {
        if (!bounded(value, MAX_DESCRIPTION_POINTS)) {
            throw invalid("Project description exceeds its character limit.");
        }
        return value;
    }

    private static boolean bounded(String value, int maximum) {
        // Both supported databases reject malformed UTF-16 and NULs differently.
        return value.indexOf(0) < 0
            && StandardCharsets.UTF_8.newEncoder().canEncode(value)
            && value.codePointCount(0, value.length()) <= maximum;
    }

    private static void requireId(String id) {
        try {
            if (id != null && id.length() == 36 && UUID.fromString(id).toString().equals(id)) {
                return;
            }
        } catch (IllegalArgumentException ignored) {
            throw invalid("Invalid project identifier.");
        }
        throw invalid("Invalid project identifier.");
    }

    private static AssistantApiException invalid(String message) {
        return new AssistantApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message);
    }

    private static AssistantApiException notFound() {
        return new AssistantApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Project not found.");
    }

    /**
     * Private project metadata.
     *
     * @param id project identifier
     * @param name display name
     * @param description private project description
     * @param createdAt record creation time
     * @param updatedAt last metadata update
     */
    public record Project(String id, String name, String description, Instant createdAt,
        Instant updatedAt) {
    }

    /**
     * Project create or update command.
     *
     * @param name supplied display name
     * @param description supplied description, with an empty string to clear it
     */
    public record ProjectWrite(String name, String description) {
    }

    /**
     * Page of private projects.
     *
     * @param items projects in the current page
     * @param nextCursor next page or null
     */
    public record ProjectPage(List<Project> items,
        @JsonInclude(JsonInclude.Include.ALWAYS) String nextCursor) {
    }

    private record Cursor(long createdAt, String id) {

        private static Cursor decode(String token, User owner) {
            if (token.length() > 180) {
                throw invalid("Invalid project cursor.");
            }
            try {
                var clear =
                    new String(Base64.getUrlDecoder().decode(token), StandardCharsets.US_ASCII);
                String[] fields = clear.split(":", -1);
                if (fields.length == 5 && "1".equals(fields[0])
                    && owner.workspaceId().equals(fields[1])
                    && owner.id().equals(fields[2])) {
                    long createdAt = Long.parseLong(fields[3]);
                    if (createdAt > 0 && UUID.fromString(fields[4]).toString().equals(fields[4])) {
                        return new Cursor(createdAt, fields[4]);
                    }
                }
            } catch (IllegalArgumentException ignored) {
                throw invalid("Invalid project cursor.");
            }
            throw invalid("Invalid project cursor.");
        }

        private static String encode(Project project, User owner) {
            String clear = "1:" + owner.workspaceId() + ":" + owner.id() + ":"
                + project.createdAt().toEpochMilli() + ":" + project.id();
            return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(clear.getBytes(StandardCharsets.US_ASCII));
        }
    }
}

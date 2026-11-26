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

package top.continew.admin.assistant.chat;

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
import top.continew.admin.assistant.privacy.AssistantErasure;
import top.continew.admin.assistant.project.AssistantProjects;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * Persists the conversation catalog of a consumer workspace.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
@Service
@ConditionalOnProperty(name = "assistant.enabled", havingValue = "true")
public class AssistantConversations {

    private static final int MAX_TITLE_LENGTH = 256;
    private static final int DEFAULT_PAGE_SIZE = 25;
    private static final int MAX_PAGE_SIZE = 100;
    private static final String ACTIVE_SCOPE =
        " WHERE workspace_id = ? AND account_id = ? AND deleted_at IS NULL";
    private static final String SELECT =
        "SELECT id, title, pinned, archived, updated_at, project_id, sharing_restricted, read_lock_revision"
            + " FROM assistant_conversation";
    private static final RowMapper<Conversation> CONVERSATION_ROW =
        (row, index) -> new Conversation(row.getString("id"), row.getString("title"),
            row.getBoolean("pinned"),
            row.getBoolean("archived"), Instant.ofEpochMilli(row.getLong("updated_at")),
            row.getString("project_id"), row.getBoolean("sharing_restricted"));
    private static final RowMapper<ConversationProjection> CATALOG_ROW =
        (row, index) -> new ConversationProjection(CONVERSATION_ROW.mapRow(row, index),
            row.getString("read_lock_revision"));

    private final JdbcTemplate jdbc;
    private final AssistantAccounts accounts;
    private final AssistantErasure erasure;
    private final AssistantProjects projects;
    private final AssistantConversationReadAccess readAccess;

    /**
     * Creates the catalog service.
     *
     * @param jdbc product data access
     * @param accounts account lifecycle lock
     * @param erasure deletion journal integration
     * @param projects private project catalog
     * @param readAccess independent conversation read authorization
     */
    public AssistantConversations(JdbcTemplate jdbc, AssistantAccounts accounts,
        AssistantErasure erasure, AssistantProjects projects,
        AssistantConversationReadAccess readAccess) {
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.erasure = erasure;
        this.projects = projects;
        this.readAccess = readAccess;
    }

    /**
     * Lists a page of conversations owned by the current consumer.
     *
     * @param user authenticated consumer with selected workspace
     * @param query requested filter and pagination controls
     * @return conversation page
     */
    public ConversationPage list(User user, ConversationQuery query) {
        int limit = query.limit() == null ? DEFAULT_PAGE_SIZE : query.limit();
        if (limit < 1 || limit > MAX_PAGE_SIZE) {
            throw invalid("Limit must be between 1 and 100.");
        }
        var sql = new StringBuilder(SELECT).append(ACTIVE_SCOPE).append(" AND read_locked = FALSE");
        var arguments = new ArrayList<Object>();
        arguments.add(user.workspaceId());
        arguments.add(user.id());
        if (query.projectId() != null) {
            if (Boolean.TRUE.equals(query.unfiled())) {
                throw invalid("Select a project or the unfiled catalog.");
            }
            projects.requireOwned(user, query.projectId());
            sql.append(" AND project_id = ?");
            arguments.add(query.projectId());
        } else if (Boolean.TRUE.equals(query.unfiled())) {
            sql.append(" AND project_id IS NULL");
        }
        if (query.archived() != null) {
            sql.append(" AND archived = ?");
            arguments.add(query.archived());
        }
        String text = query.q() == null ? "" : query.q().strip();
        if (!text.isEmpty()) {
            if (text.length() > MAX_TITLE_LENGTH || text.indexOf(0) >= 0) {
                throw invalid("Invalid conversation search text.");
            }
            sql.append("""
                 AND (title_search LIKE ? ESCAPE '!' OR EXISTS (
                    SELECT 1 FROM assistant_chat_message m
                    WHERE m.conversation_id = assistant_conversation.id
                      AND LOWER(m.content) LIKE ? ESCAPE '!'
                ))
                """);
            String pattern = "%" + escapeLike(normalizeSearch(text)) + "%";
            arguments.add(pattern);
            arguments.add(pattern);
        }
        if (query.cursor() != null) {
            Cursor cursor = Cursor.decode(query.cursor());
            sql.append(" AND (pinned, updated_at, id) < (?, ?, ?)");
            arguments.add(cursor.pinned());
            arguments.add(cursor.updatedAt());
            arguments.add(cursor.id());
        }
        sql.append(" ORDER BY pinned DESC, updated_at DESC, id DESC LIMIT ?");
        arguments.add(limit + 1);
        String revision = readAccess.scopeRevision(user.id(), user.workspaceId());
        List<ConversationProjection> candidates =
            jdbc.query(sql.toString(), CATALOG_ROW, arguments.toArray());
        readAccess.verifyScope(user.id(), user.workspaceId(), revision);
        if (candidates.stream().anyMatch(row -> !readAccess.open(user.id(), user.workspaceId(),
            row.conversation().id(), false, row.revision()))) {
            throw new AssistantApiException(HttpStatus.CONFLICT, "CONVERSATION_LOCK_CHANGED",
                "A conversation lock changed. Retry with the current access state.");
        }
        List<Conversation> found = candidates.stream().map(ConversationProjection::conversation)
            .toList();
        if (found.size() <= limit) {
            return new ConversationPage(found, null);
        }
        return new ConversationPage(found.subList(0, limit), Cursor.encode(found.get(limit - 1)));
    }

    /**
     * Creates a conversation in a selected workspace.
     *
     * @param user authenticated consumer with selected workspace
     * @param body create request
     * @return newly created conversation
     */
    @Transactional
    public Conversation create(User user, ConversationCreate body) {
        accounts.lockActive(user.id());
        String title = body.title() == null ? "New conversation" : validatedTitle(body.title());
        String projectId = body.projectId();
        if (projectId != null) {
            projects.requireOwned(user, projectId);
        }
        String id = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        jdbc.update("""
            INSERT INTO assistant_conversation
                (id, workspace_id, account_id, title, title_search, pinned, archived,
                 created_at, updated_at, project_id)
            VALUES (?, ?, ?, ?, ?, false, false, ?, ?, ?)
            """, id, user.workspaceId(), user.id(), title, normalizeSearch(title), now, now,
            projectId);
        return new Conversation(id, title, false, false, Instant.ofEpochMilli(now), projectId,
            false);
    }

    /**
     * Reads an owned conversation.
     *
     * @param user authenticated consumer with selected workspace
     * @param id conversation identifier
     * @return conversation metadata
     */
    public Conversation get(User user, String id) {
        return scopedConversation(user, id, false);
    }

    /**
     * Atomically changes the project of an owned conversation.
     *
     * @param user authenticated consumer in the selected workspace
     * @param id conversation identifier
     * @param projectId destination project ID or null for the unfiled catalog
     * @return current conversation metadata
     * @since 4.2.0
     */
    @Transactional
    public Conversation move(User user, String id, String projectId) {
        accounts.lockActive(user.id());
        Conversation current = scopedConversation(user, id, true);
        if (projectId != null) {
            projects.requireOwned(user, projectId);
        }
        if (Objects.equals(current.projectId(), projectId)) {
            return current;
        }
        if (projectId != null) {
            erasure.revokePublicSharesForFiling(user, id);
        }
        erasure.fenceHistoryPlacement(user, id);
        long updatedAt = Math.max(System.currentTimeMillis(), current.updatedAt().toEpochMilli());
        jdbc.update(
            "UPDATE assistant_conversation SET project_id = ?, updated_at = ?" + ACTIVE_SCOPE
                + " AND id = ?",
            projectId, updatedAt, user.workspaceId(), user.id(), id);
        return new Conversation(id, current.title(), current.pinned(), current.archived(),
            Instant.ofEpochMilli(updatedAt), projectId, current.sharingRestricted());
    }

    /**
     * Changes the sharing restriction while protecting the owner and source against concurrent publication.
     *
     * @param user authenticated owner in the selected workspace
     * @param id owned conversation identifier
     * @param restricted whether sharing should be disabled and existing links revoked
     * @return conversation metadata and current restriction
     */
    @Transactional
    public Conversation setSharingRestriction(User user, String id, boolean restricted) {
        accounts.lockActive(user.id());
        Conversation current = scopedConversation(user, id, true);
        if (current.sharingRestricted() != restricted) {
            erasure.changeSharingRestriction(user, id, restricted);
            return scopedConversation(user, id, false);
        }
        return current;
    }

    /**
     * Updates the supplied conversation fields without overwriting concurrent independent edits.
     *
     * @param user authenticated consumer with selected workspace
     * @param id conversation identifier
     * @param patch fields to update
     * @return updated conversation metadata
     */
    @Transactional
    public Conversation update(User user, String id, ConversationPatch patch) {
        if (patch.title() == null && patch.pinned() == null && patch.archived() == null) {
            return get(user, id);
        }
        String title = patch.title() == null ? null : validatedTitle(patch.title());
        String titleSearch = title == null ? null : normalizeSearch(title);
        int changed = jdbc.update("""
            UPDATE assistant_conversation
            SET title = COALESCE(?, title), title_search = COALESCE(?, title_search),
                pinned = COALESCE(?, pinned), archived = COALESCE(?, archived),
                updated_at = GREATEST(updated_at, ?)
            """ + ACTIVE_SCOPE + " AND id = ?", title, titleSearch, patch.pinned(),
            patch.archived(),
            System.currentTimeMillis(), user.workspaceId(), user.id(), id);
        if (changed == 0) {
            throw notFound();
        }
        return get(user, id);
    }

    /**
     * Removes an owned conversation from online access while retaining its deletion marker.
     *
     * @param user authenticated consumer with selected workspace
     * @param id conversation identifier
     */
    public void delete(User user, String id) {
        if (erasure.enabled()) {
            erasure.deleteConversation(user, id);
            return;
        }
        int changed = jdbc.update("""
            UPDATE assistant_conversation SET deleted_at = ?, title = '', title_search = ''
            """ + ACTIVE_SCOPE + " AND id = ?", System.currentTimeMillis(), user.workspaceId(),
            user.id(), id);
        if (changed == 0) {
            throw notFound();
        }
    }

    private Conversation scopedConversation(User user, String id, boolean lock) {
        var proof = readAccess.require(user.id(), user.workspaceId(), id);
        String sql = SELECT + ACTIVE_SCOPE + " AND id = ? AND read_locked = FALSE"
            + (lock ? " FOR UPDATE" : "");
        var result = jdbc.query(sql, CONVERSATION_ROW, user.workspaceId(), user.id(), id).stream()
            .findFirst();
        readAccess.verify(proof);
        return result.orElseThrow(AssistantConversations::notFound);
    }

    private static String validatedTitle(String title) {
        String trimmed = title.strip();
        if (trimmed.isEmpty() || trimmed.length() > MAX_TITLE_LENGTH || trimmed.indexOf(0) >= 0) {
            throw invalid("Title must contain between 1 and 256 characters.");
        }
        return trimmed;
    }

    private static String normalizeSearch(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
    }

    private static String escapeLike(String text) {
        return text.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }

    private static AssistantApiException invalid(String message) {
        return new AssistantApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message);
    }

    private static AssistantApiException notFound() {
        return new AssistantApiException(HttpStatus.NOT_FOUND, "NOT_FOUND",
            "Conversation not found.");
    }

    /**
     * Metadata returned by the conversation catalog.
     *
     * @param id conversation identifier
     * @param title display title
     * @param pinned whether shown before other conversations
     * @param archived whether archived
     * @param updatedAt last metadata change
     * @param projectId associated private project or null when unfiled
     * @param sharingRestricted whether publishing and consuming shares is disabled
     */
    public record Conversation(String id, String title, boolean pinned, boolean archived,
        Instant updatedAt, @JsonInclude(JsonInclude.Include.NON_NULL) String projectId,
        boolean sharingRestricted) {
    }

    /**
     * New conversation input.
     *
     * @param title optional display title
     * @param projectId optional project in which to create the conversation
     */
    public record ConversationCreate(String title, String projectId) {
    }

    /**
     * Changes to conversation metadata.
     *
     * @param title replacement title
     * @param pinned replacement pin status
     * @param archived replacement archive status
     */
    public record ConversationPatch(String title, Boolean pinned, Boolean archived) {
    }

    /**
     * Filter and pagination metadata for a catalog query.
     *
     * @param q optional search term for titles and message content
     * @param archived optional archive status
     * @param limit optional page size
     * @param cursor optional opaque continuation
     * @param projectId optional project filter
     * @param unfiled whether only conversations outside projects are returned
     */
    public record ConversationQuery(String q, Boolean archived, Integer limit, String cursor,
        String projectId, Boolean unfiled) {
    }

    /**
     * Conversation page and its continuation.
     *
     * @param items catalog rows
     * @param nextCursor opaque continuation or null at the end
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ConversationPage(List<Conversation> items, String nextCursor) {
    }

    private record ConversationProjection(Conversation conversation, String revision) {
    }

    private record Cursor(boolean pinned, long updatedAt, String id) {

        private static String encode(Conversation conversation) {
            String key = "1:" + (conversation.pinned() ? "1" : "0") + ":"
                + conversation.updatedAt().toEpochMilli() + ":" + conversation.id();
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                key.getBytes(StandardCharsets.US_ASCII));
        }

        private static Cursor decode(String token) {
            if (token.length() > 100) {
                throw invalid("Invalid conversation cursor.");
            }
            try {
                String key =
                    new String(Base64.getUrlDecoder().decode(token), StandardCharsets.US_ASCII);
                String[] parts = key.split(":", -1);
                if (parts.length == 4 && "1".equals(parts[0])) {
                    long updatedAt = Long.parseLong(parts[2]);
                    String id = UUID.fromString(parts[3]).toString();
                    if (updatedAt >= 0 && id.equals(parts[3])) {
                        if ("1".equals(parts[1]) || "0".equals(parts[1])) {
                            return new Cursor("1".equals(parts[1]), updatedAt, id);
                        }
                    }
                }
            } catch (IllegalArgumentException ignored) {
                throw invalid("Invalid conversation cursor.");
            }
            throw invalid("Invalid conversation cursor.");
        }
    }
}

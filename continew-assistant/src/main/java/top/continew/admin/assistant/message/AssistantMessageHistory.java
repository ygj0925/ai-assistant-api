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

package top.continew.admin.assistant.message;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import top.continew.admin.assistant.account.AssistantAccounts;
import top.continew.admin.assistant.account.AssistantApiException;
import top.continew.admin.assistant.chat.AssistantConversationReadAccess;
import top.continew.admin.assistant.run.AssistantRunContext;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * Reads stored conversation messages in chronological order.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
@Service
@ConditionalOnProperty(name = "assistant.enabled", havingValue = "true")
public class AssistantMessageHistory {

    private final JdbcTemplate jdbc;
    private final AssistantConversationReadAccess readAccess;

    /**
     * Creates the message history service.
     *
     * @param jdbc product data access
     * @param readAccess current durable policy and scope
     */
    public AssistantMessageHistory(JdbcTemplate jdbc, AssistantConversationReadAccess readAccess) {
        this.jdbc = jdbc;
        this.readAccess = readAccess;
    }

    /**
     * Reads a page of messages visible to the consumer.
     *
     * @param principal authenticated consumer and workspace
     * @param conversationId conversation ID
     * @param cursor opaque pagination cursor
     * @param limit requested page size
     * @return chronological message page
     */
    public MessagePage list(AssistantAccounts.Principal principal, String conversationId,
        String cursor, int limit) {
        if (limit < 1 || limit > 100) {
            throw invalid("Limit must be between 1 and 100.");
        }
        Cursor after = parseCursor(cursor);
        String accountId = principal.user().id();
        String workspaceId = principal.user().workspaceId();
        var proof = readAccess.require(accountId, workspaceId, conversationId);
        List<Message> rows = jdbc.query(
            """
                SELECT m.id, m.parent_id, m.role, m.content, m.created_at, r.model_id, r.model_version,
                    r.context_budget, r.context_used, r.context_turns, r.context_trimmed,
                    r.fallback_from_model_id, b.used_at,
                    EXISTS (SELECT 1 FROM assistant_run_project_memory_source s
                        WHERE s.run_id = r.id) AS project_memory_used
                FROM assistant_chat_message m
                JOIN assistant_conversation c ON c.id = m.conversation_id
                LEFT JOIN assistant_run r ON r.response_message_id = m.id AND r.conversation_id = c.id
                    AND r.account_id = c.account_id AND r.workspace_id = c.workspace_id
                LEFT JOIN assistant_run_history_reference b ON b.run_id = r.id AND b.used_at IS NOT NULL
                WHERE c.id = ? AND c.account_id = ? AND c.workspace_id = ? AND c.deleted_at IS NULL
                  AND c.read_locked = FALSE
                  AND (m.created_at > ? OR (m.created_at = ? AND m.id > ?))
                ORDER BY m.created_at, m.id
                LIMIT ?
                """,
            (row, index) -> {
                String model = row.getString("model_id");
                Generation generation = model == null ? null : new Generation(model,
                    row.getString("model_version"), AssistantRunContext.read(row),
                    row.getString("fallback_from_model_id"),
                    row.getObject("used_at") == null ? null : Boolean.TRUE,
                    row.getBoolean("project_memory_used") ? Boolean.TRUE : null);
                return new Message(row.getString("id"), row.getString("parent_id"),
                    row.getString("role"), row.getString("content"),
                    Instant.ofEpochMilli(row.getLong("created_at")), generation);
            },
            conversationId, accountId, workspaceId, after.createdAt(), after.createdAt(),
            after.id(), limit + 1);
        readAccess.verify(proof);
        if (rows.size() <= limit) {
            return new MessagePage(List.copyOf(rows), null);
        }
        Message last = rows.get(limit - 1);
        String next = Base64.getUrlEncoder().withoutPadding().encodeToString(
            (last.createdAt().toEpochMilli() + ":" + last.id())
                .getBytes(StandardCharsets.US_ASCII));
        return new MessagePage(List.copyOf(rows.subList(0, limit)), next);
    }

    private static Cursor parseCursor(String encoded) {
        if (encoded == null) {
            return new Cursor(0L, "");
        }
        if (encoded.length() > 80) {
            throw invalid("Invalid message cursor.");
        }
        try {
            String decoded =
                new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.US_ASCII);
            String[] parts = decoded.split(":", -1);
            if (parts.length == 2) {
                long createdAt = Long.parseLong(parts[0]);
                if (createdAt > 0 && UUID.fromString(parts[1]).toString().equals(parts[1])) {
                    return new Cursor(createdAt, parts[1]);
                }
            }
        } catch (IllegalArgumentException ignored) {
            throw invalid("Invalid message cursor.");
        }
        throw invalid("Invalid message cursor.");
    }

    private static AssistantApiException invalid(String message) {
        return new AssistantApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message);
    }

    /**
     * Stored message with its branch parent.
     *
     * @param id message ID
     * @param parentId previous message or null for the root
     * @param role user or assistant
     * @param content plain stored message
     * @param createdAt timestamp of the message
     * @param generation immutable model metadata when available
     */
    public record Message(String id, @JsonInclude(JsonInclude.Include.ALWAYS) String parentId,
        String role, String content, Instant createdAt,
        @JsonInclude(JsonInclude.Include.NON_NULL) Generation generation) {
    }

    /**
     * Model information attached to a generated assistant message.
     *
     * @param modelId recorded model ID
     * @param modelVersion recorded model version
     * @param context context selection recorded with the message
     * @param fallbackFromModelId retired model originally requested, when applicable
     * @param historyReferenceUsed whether a private history receipt exists
     * @param projectMemorySourcesUsed whether a private project memory receipt exists
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Generation(String modelId, String modelVersion, AssistantRunContext context,
        String fallbackFromModelId, Boolean historyReferenceUsed,
        Boolean projectMemorySourcesUsed) {
    }

    /**
     * Chronological message page.
     *
     * @param items visible messages
     * @param nextCursor continuation cursor or null
     */
    public record MessagePage(List<Message> items,
        @JsonInclude(JsonInclude.Include.ALWAYS) String nextCursor) {
    }

    private record Cursor(long createdAt, String id) {
    }
}

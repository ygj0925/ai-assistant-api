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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import top.continew.admin.assistant.account.AssistantAccounts;
import top.continew.admin.assistant.account.AssistantAccounts.Principal;
import top.continew.admin.assistant.account.AssistantAccounts.User;
import top.continew.admin.assistant.account.AssistantApiException;
import top.continew.admin.assistant.privacy.AssistantDeletionJournal;
import top.continew.admin.assistant.privacy.AssistantErasure;

import java.util.List;
import java.util.UUID;

/**
 * Controls a password-recoverable conversation read lock independent of link sharing policy.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
@Service
@ConditionalOnProperty(name = "assistant.enabled", havingValue = "true")
public class AssistantConversationReadLock {

    private static final String LOCKED = """
        FROM assistant_conversation WHERE account_id = ? AND workspace_id = ?
            AND deleted_at IS NULL AND read_locked = TRUE
        """;
    private final JdbcTemplate jdbc;
    private final AssistantAccounts accounts;
    private final AssistantDeletionJournal journal;
    private final AssistantErasure erasure;
    private final boolean creationEnabled;

    /**
     * Connects scoped customer credentials and a separately recoverable policy.
     *
     * @param jdbc body-free conversation and session records
     * @param accounts account/session authority
     * @param journal durable privacy binding
     * @param erasure policy and derived publication retirement
     * @param creationEnabled explicit new-lock rollout
     */
    public AssistantConversationReadLock(JdbcTemplate jdbc, AssistantAccounts accounts,
        AssistantDeletionJournal journal, AssistantErasure erasure,
        @Value("${assistant.conversation-read-lock.enabled:false}") boolean creationEnabled) {
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.journal = journal;
        this.erasure = erasure;
        this.creationEnabled = creationEnabled;
    }

    /**
     * Reports whether a customer may apply a new read lock.
     *
     * @return configured and recoverable new lock capability
     */
    public boolean creationAvailable() {
        return creationEnabled && journal.acceptsErasure();
    }

    /**
     * Reports whether the caller has an existing lock or may create one.
     *
     * @param user validated current account and selected workspace
     * @return whether to expose locked conversation recovery
     */
    public boolean catalogueAvailable(User user) {
        return journal.acceptsErasure() && (creationEnabled || !jdbc.queryForList(
            "SELECT id " + LOCKED + " LIMIT 1", String.class, user.id(), user.workspaceId())
            .isEmpty());
    }

    /**
     * Lists only opaque identifiers with no metadata, title or update history.
     *
     * @param user authenticated owner and selected workspace
     * @param cursor previous ID or null
     * @param limit requested bounded page size
     * @return private lock identities
     */
    public LockedPage catalogue(User user, String cursor, int limit) {
        requireJournal();
        if (cursor != null) {
            requireId(cursor);
        }
        if (limit < 1 || limit > 100) {
            throw invalid("Locked conversation pages must contain between 1 and 100 items.");
        }
        List<String> rows = jdbc.queryForList(
            "SELECT id " + LOCKED + " AND id > ? ORDER BY id LIMIT ?",
            String.class, user.id(), user.workspaceId(), cursor == null ? "" : cursor, limit + 1);
        if (rows.size() <= limit) {
            return new LockedPage(rows.stream().map(LockedItem::new).toList(), null);
        }
        return new LockedPage(rows.subList(0, limit).stream().map(LockedItem::new).toList(),
            rows.get(limit - 1));
    }

    /**
     * Applies a new strict read lock with explicit user confirmation.
     *
     * @param principal current authenticated owner and session
     * @param id targeted conversation
     * @param confirm explicit privacy decision
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void lock(Principal principal, String id, boolean confirm) {
        if (!creationEnabled) {
            throw unavailable(HttpStatus.FORBIDDEN);
        }
        requireJournal();
        if (!confirm) {
            throw invalid("Confirm locking the conversation.");
        }
        requireId(id);
        accounts.lockActive(principal.user().id());
        set(principal, id, true);
    }

    /**
     * Opens private authorized reads only after verifying the same device's current password.
     *
     * @param principal current authenticated owner and session
     * @param id targeted conversation
     * @param currentPassword current account credential
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void unlock(Principal principal, String id, String currentPassword) {
        requireJournal();
        requireId(id);
        accounts.requirePrivacyPassword(principal, currentPassword);
        set(principal, id, false);
    }

    private void set(Principal principal, String id, boolean desired) {
        User user = principal.user();
        List<String> membership = jdbc.queryForList("""
            SELECT m.membership_revision FROM assistant_workspace_member m
            JOIN assistant_session s ON s.account_id = m.account_id
            WHERE m.account_id = ? AND m.workspace_id = ? AND s.id = ? AND s.expires_at > ?
            FOR UPDATE
            """, String.class, user.id(), user.workspaceId(), principal.sessionId(),
            System.currentTimeMillis());
        if (membership.isEmpty() || !accounts.allowsMembership(user.id(), user.workspaceId(),
            membership.get(0))) {
            throw new AssistantApiException(HttpStatus.FORBIDDEN, "FORBIDDEN",
                "An active workspace session is required.");
        }
        boolean current = jdbc.query("""
            SELECT read_locked FROM assistant_conversation
            WHERE id = ? AND account_id = ? AND workspace_id = ? AND deleted_at IS NULL
            """, (row, index) -> row.getBoolean("read_locked"), id, user.id(), user.workspaceId())
            .stream().findFirst().orElseThrow(AssistantConversationReadLock::missing);
        if (current != desired) {
            erasure.changeConversationReadLock(user, id, desired);
        }
    }

    private void requireJournal() {
        if (!journal.enabled()) {
            throw unavailable(HttpStatus.FORBIDDEN);
        }
        if (!journal.acceptsErasure()) {
            throw unavailable(HttpStatus.SERVICE_UNAVAILABLE);
        }
    }

    private static void requireId(String id) {
        try {
            if (id != null && id.length() == 36 && UUID.fromString(id).toString().equals(id)) {
                return;
            }
        } catch (IllegalArgumentException failure) {
            throw invalid("Invalid conversation identifier.");
        }
        throw invalid("Invalid conversation identifier.");
    }

    private static AssistantApiException missing() {
        return new AssistantApiException(HttpStatus.NOT_FOUND, "NOT_FOUND",
            "Conversation not found.");
    }

    private static AssistantApiException invalid(String message) {
        return new AssistantApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message);
    }

    private static AssistantApiException unavailable(HttpStatus status) {
        return new AssistantApiException(status, "CAPABILITY_UNAVAILABLE",
            "Conversation locking is unavailable.");
    }

    /**
     * Opaque identity of a locked item.
     *
     * @param id authorized conversation identifier
     */
    public record LockedItem(String id) {
    }

    /**
     * Page of authorized opaque lock identities.
     *
     * @param items locked conversations without metadata
     * @param nextCursor continuation or null
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record LockedPage(List<LockedItem> items, String nextCursor) {
    }
}

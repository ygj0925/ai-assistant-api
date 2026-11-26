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

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import top.continew.admin.assistant.account.AssistantApiException;
import top.continew.admin.assistant.privacy.AssistantDeletionJournal;

import java.util.Objects;

/**
 * Gates conversation reads with the exact current database and independent journal revision.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
@Component
@ConditionalOnProperty(name = "assistant.enabled", havingValue = "true")
public class AssistantConversationReadAccess {

    private final JdbcTemplate jdbc;
    private final AssistantDeletionJournal journal;

    /**
     * Uses a body-free authoritative projection and the independent privacy journal.
     *
     * @param jdbc scoped conversation policy
     * @param journal durable read-policy transitions
     */
    public AssistantConversationReadAccess(JdbcTemplate jdbc, AssistantDeletionJournal journal) {
        this.jdbc = jdbc;
        this.journal = journal;
    }

    /**
     * Reads current scope without fetching a name, prompt, reply or author details.
     *
     * @param accountId conversation owner
     * @param workspaceId selected workspace
     * @param conversationId exact conversation
     * @return authorized scope and observed read generation
     */
    public Witness require(String accountId, String workspaceId, String conversationId) {
        Policy policy = jdbc
            .query(
                """
                    SELECT c.read_locked, c.read_lock_revision, m.membership_revision
                    FROM assistant_conversation c
                    JOIN assistant_workspace_member m ON m.account_id = c.account_id AND m.workspace_id = c.workspace_id
                    WHERE c.id = ? AND c.workspace_id = ? AND c.account_id = ? AND c.deleted_at IS NULL
                    """,
                (row, index) -> new Policy(row.getBoolean("read_locked"),
                    row.getString("read_lock_revision"), row.getString("membership_revision")),
                conversationId, workspaceId, accountId)
            .stream().findFirst()
            .orElseThrow(AssistantConversationReadAccess::missing);
        Witness proof = new Witness(accountId, workspaceId, conversationId, policy.revision(),
            policy.membershipRevision());
        if (policy.locked()) {
            throw locked();
        }
        verify(proof);
        return proof;
    }

    /**
     * Verifies a previously authorized body read has not crossed any policy transition.
     *
     * @param proof original authorized read scope
     */
    public void verify(Witness proof) {
        if (journal.blocksWorkspaceMembership(proof.accountId(), proof.workspaceId(),
            proof.membershipRevision())) {
            throw missing();
        }
        if (!open(proof.accountId(), proof.workspaceId(), proof.conversationId(), false,
            proof.revision())) {
            throw locked();
        }
    }

    /**
     * Checks a policy already selected with a scoped database row.
     *
     * @param accountId conversation owner
     * @param workspaceId owning workspace
     * @param conversationId source conversation
     * @param locked persisted read-lock state
     * @param revision exact persisted generation
     * @return whether the row is still open to its authorized reader
     */
    public boolean open(String accountId, String workspaceId, String conversationId, boolean locked,
        String revision) {
        return !locked && !journal.blocksConversationRead(accountId, workspaceId, conversationId,
            revision);
    }

    /**
     * Captures the owner/workspace marker for a projection containing multiple conversations.
     *
     * @param accountId consumer account
     * @param workspaceId selected workspace
     * @return current scope-wide read policy marker or null for legacy open policy
     */
    public String scopeRevision(String accountId, String workspaceId) {
        return journal.conversationReadFence(accountId, workspaceId);
    }

    /**
     * Rejects a multi-conversation projection if any read policy changed during assembly.
     *
     * @param accountId consumer account
     * @param workspaceId selected workspace
     * @param revision marker from before reading
     */
    public void verifyScope(String accountId, String workspaceId, String revision) {
        if (!Objects.equals(revision, scopeRevision(accountId, workspaceId))) {
            throw new AssistantApiException(HttpStatus.CONFLICT, "CONVERSATION_LOCK_CHANGED",
                "A conversation lock changed. Retry with the current access state.");
        }
    }

    private static AssistantApiException missing() {
        return new AssistantApiException(HttpStatus.NOT_FOUND, "NOT_FOUND",
            "Conversation not found.");
    }

    private static AssistantApiException locked() {
        return new AssistantApiException(HttpStatus.LOCKED, "CONVERSATION_LOCKED",
            "Unlock this conversation before reading it.");
    }

    /**
     * Original authorized conversation and membership generation, without body or title.
     *
     * @param accountId conversation owner
     * @param workspaceId selected workspace
     * @param conversationId exact conversation
     * @param revision read authorization generation
     * @param membershipRevision workspace membership generation
     */
    public record Witness(String accountId, String workspaceId, String conversationId,
        String revision, String membershipRevision) {
    }

    private record Policy(boolean locked, String revision, String membershipRevision) {
    }
}

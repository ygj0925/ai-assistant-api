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

package top.continew.admin.assistant.memory;

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
import top.continew.admin.assistant.account.AssistantApiException;
import top.continew.admin.assistant.chat.AssistantConversationReadAccess;
import top.continew.admin.assistant.chat.AssistantConversationReadAccess.Witness;
import top.continew.admin.assistant.memory.AssistantGenerationMemory.Proof;
import top.continew.admin.assistant.memory.AssistantGenerationMemory.Scope;
import top.continew.admin.assistant.privacy.AssistantDeletionJournal;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Records the project memory entries actually used in a completed local generation.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
@Service
@ConditionalOnProperty(name = "assistant.enabled", havingValue = "true")
public class AssistantProjectMemorySources {

    private static final int MAX_EXCERPT = 120;
    private static final Source UNAVAILABLE = new Source("unavailable", null, null);

    private final JdbcTemplate jdbc;
    private final AssistantAccounts accounts;
    private final AssistantConversationReadAccess readAccess;
    private final AssistantDeletionJournal journal;
    private final boolean enabled;

    /**
     * Wires the receipt store under a separate rollout flag.
     *
     * @param jdbc assistant data access
     * @param accounts account lifecycle guard
     * @param readAccess current conversation read authorization
     * @param journal independent revocation journal
     * @param enabled project-memory source receipt flag
     * @param memoryEnabled manual memory flag
     * @param contextEnabled local generation context flag
     */
    public AssistantProjectMemorySources(JdbcTemplate jdbc, AssistantAccounts accounts,
        AssistantConversationReadAccess readAccess, AssistantDeletionJournal journal,
        @Value("${assistant.memory.source-receipts-enabled:false}") boolean enabled,
        @Value("${assistant.memory.enabled:false}") boolean memoryEnabled,
        @Value("${assistant.memory.context-enabled:false}") boolean contextEnabled) {
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.readAccess = readAccess;
        this.journal = journal;
        this.enabled = enabled && memoryEnabled && contextEnabled;
    }

    Access capture(Scope scope) {
        if (!enabled || scope.projectId() == null) {
            return null;
        }
        var witness =
            readAccess.require(scope.accountId(), scope.workspaceId(), scope.conversationId());
        return access(witness);
    }

    boolean current(Scope scope, Access expected) {
        if (expected == null) {
            return true;
        }
        try {
            return expected.equals(capture(scope));
        } catch (AssistantApiException ignored) {
            return false;
        }
    }

    void record(String runId, Proof proof) {
        Access access = proof.sourceAccess();
        if (access == null) {
            return;
        }
        Scope scope = proof.scope();
        List<String> ids = proof.entryIds();
        for (int index = 0; index < ids.size(); index++) {
            jdbc.update("""
                INSERT INTO assistant_run_project_memory_source
                    (run_id, source_order, account_id, workspace_id, conversation_id, project_id,
                     entry_id, memory_epoch, membership_revision, read_revision, placement_revision)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, runId, index + 1, scope.accountId(), scope.workspaceId(),
                scope.conversationId(),
                scope.projectId(), ids.get(index), proof.memoryEpoch(), access.membershipRevision(),
                access.readRevision(), access.placementRevision());
        }
    }

    /**
     * Shows current excerpts only for source entries still readable by the reply owner.
     *
     * @param actor authenticated consumer and workspace
     * @param conversationId conversation owning the reply
     * @param replyId one completed reply with a source receipt
     * @return sources ordered by use, with unavailable entries redacted
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Sources sources(Principal actor, String conversationId, String replyId) {
        if (!uuid(conversationId) || !uuid(replyId)) {
            throw new AssistantApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                "A conversation and reply identifier are required.");
        }
        var owner = actor.user();
        accounts.lockActive(owner.id());
        Witness witness = readAccess.require(owner.id(), owner.workspaceId(), conversationId);
        Access current = access(witness);
        List<Receipt> receipts = jdbc.query("""
            SELECT s.project_id, s.entry_id, s.memory_epoch, s.membership_revision,
                s.read_revision, s.placement_revision
            FROM assistant_run_project_memory_source s
            JOIN assistant_run r ON r.id = s.run_id
            WHERE s.account_id = ? AND s.workspace_id = ? AND s.conversation_id = ?
                AND r.response_message_id = ? AND r.state = 'completed'
            ORDER BY s.source_order
            """, (row, index) -> new Receipt(row.getString("project_id"), row.getString("entry_id"),
            row.getLong("memory_epoch"), new Access(row.getString("membership_revision"),
                row.getString("read_revision"), row.getString("placement_revision"))),
            owner.id(), owner.workspaceId(), conversationId, replyId);
        if (receipts.isEmpty()) {
            throw new AssistantApiException(HttpStatus.NOT_FOUND, "NOT_FOUND",
                "Project memory sources not found.");
        }
        if (!enabled || !journal.acceptsErasure()) {
            return new Sources(receipts.stream().map(receipt -> UNAVAILABLE).toList());
        }
        List<Source> result = receipts.stream()
            .map(receipt -> !current.equals(receipt.access()) ? UNAVAILABLE
                : excerpt(witness, receipt)
                    .map(text -> new Source("available", receipt.entryId(), text))
                    .orElse(UNAVAILABLE))
            .toList();
        readAccess.verify(witness);
        return new Sources(result);
    }

    private Optional<String> excerpt(Witness witness, Receipt receipt) {
        if (receipt.entryId() == null || journal.blocksMemoryEntry(
            witness.accountId(), witness.workspaceId(), receipt.entryId())
            || receipt.epoch() <= journal.memoryRevocations(witness.accountId(),
                witness.workspaceId(), receipt.projectId()).memory()) {
            return Optional.empty();
        }
        return jdbc.queryForList("""
            SELECT LEFT(e.content, ?)
            FROM assistant_conversation c
            JOIN assistant_project p ON p.id = c.project_id AND p.account_id = c.account_id
                AND p.workspace_id = c.workspace_id AND p.deleted_at IS NULL
            JOIN assistant_memory_settings s ON s.scope_id = p.id AND s.account_id = p.account_id
                AND s.workspace_id = p.workspace_id AND s.scope_kind = 'project'
            JOIN assistant_memory_entry e ON e.scope_id = s.scope_id AND e.account_id = s.account_id
                AND e.workspace_id = s.workspace_id AND e.scope_kind = s.scope_kind
            WHERE c.id = ? AND p.account_id = ? AND p.workspace_id = ? AND p.id = ?
                AND s.memory_enabled = TRUE AND s.memory_epoch = ?
                AND e.id = ? AND e.deleted_at IS NULL
            """, String.class, MAX_EXCERPT, witness.conversationId(), witness.accountId(),
            witness.workspaceId(), receipt.projectId(), receipt.epoch(), receipt.entryId())
            .stream().findFirst();
    }

    private Access access(Witness witness) {
        return new Access(witness.membershipRevision(), witness.revision(),
            journal.historyPlacementRevision(witness.accountId(), witness.workspaceId(),
                witness.conversationId()));
    }

    private static boolean uuid(String value) {
        if (value == null || value.length() != 36) {
            return false;
        }
        try {
            return Objects.equals(value, UUID.fromString(value).toString());
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    /**
     * Authority under which the source was selected, without any source content.
     *
     * @param membershipRevision active workspace membership generation
     * @param readRevision conversation access generation
     * @param placementRevision journal generation of the conversation's project placement
     */
    public record Access(String membershipRevision, String readRevision, String placementRevision) {
    }

    /**
     * The currently available excerpt, or an unavailable source without identifiers.
     *
     * @param status current source availability
     * @param entryId memory identifier when authorized
     * @param excerpt current text excerpt when authorized
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Source(String status, String entryId, String excerpt) {
    }

    /**
     * Original source order with withdrawn entries redacted.
     *
     * @param items ordered sources
     */
    public record Sources(List<Source> items) {
    }

    private record Receipt(String projectId, String entryId, long epoch, Access access) {
    }
}

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

package top.continew.admin.assistant.run;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import top.continew.admin.assistant.account.AssistantAccounts;
import top.continew.admin.assistant.account.AssistantAccounts.Principal;
import top.continew.admin.assistant.account.AssistantAccounts.User;
import top.continew.admin.assistant.account.AssistantApiException;
import top.continew.admin.assistant.chat.AssistantConversationReadAccess;
import top.continew.admin.assistant.memory.AssistantGenerationMemory.Kind;
import top.continew.admin.assistant.memory.AssistantGenerationMemory.Material;
import top.continew.admin.assistant.model.AssistantModels;
import top.continew.admin.assistant.privacy.AssistantDeletionJournal;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Authorizes one explicitly selected assistant reply using only owned, personal conversation data.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
@Service
@ConditionalOnProperty(name = "assistant.enabled", havingValue = "true")
public class AssistantHistoryReferences {

    static final int MAX_POINTS = 256;

    private static final String BINDINGS = """
        SELECT b.target_conversation_id, b.source_conversation_id, b.source_message_id,
            b.history_epoch, b.source_read_revision, b.target_read_revision,
            b.source_placement_revision, b.target_placement_revision
        FROM assistant_run_history_reference b
        """;
    private static final RowMapper<Binding> BINDING =
        (row, index) -> new Binding(row.getString("target_conversation_id"),
            new Source(row.getString("source_conversation_id"), row.getString("source_message_id")),
            row.getLong("history_epoch"), row.getString("source_read_revision"),
            row.getString("target_read_revision"), row.getString("source_placement_revision"),
            row.getString("target_placement_revision"));
    private static final String SOURCE_REPLY =
        """
            FROM assistant_chat_message m
            JOIN assistant_run r ON r.response_message_id = m.id AND r.conversation_id = m.conversation_id
            WHERE m.conversation_id = ? AND m.id = ? AND m.role = 'assistant'
                AND r.account_id = ? AND r.workspace_id = ? AND r.state = 'completed'
            """;
    private static final Receipt UNAVAILABLE =
        new Receipt("history_reply", "unavailable", null, null);

    private final JdbcTemplate jdbc;
    private final AssistantAccounts accounts;
    private final AssistantModels models;
    private final AssistantConversationReadAccess readAccess;
    private final AssistantDeletionJournal journal;
    private final boolean enabled;

    /**
     * Creates the storage and consent adapter for explicitly selected prior replies.
     *
     * @param jdbc assistant data source
     * @param accounts owner lifecycle locks
     * @param models current model catalogue
     * @param readAccess current conversation read policy
     * @param journal independent revocation state
     * @param enabled explicit history feature flag
     * @param memoryEnabled memory preferences feature flag
     */
    public AssistantHistoryReferences(JdbcTemplate jdbc, AssistantAccounts accounts,
        AssistantModels models, AssistantConversationReadAccess readAccess,
        AssistantDeletionJournal journal,
        @Value("${assistant.history-references.enabled:false}") boolean enabled,
        @Value("${assistant.memory.enabled:false}") boolean memoryEnabled) {
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.models = models;
        this.readAccess = readAccess;
        this.journal = journal;
        this.enabled = enabled && memoryEnabled;
    }

    /**
     * Reports whether the capability is configured for this personal scope. Consent is separate.
     *
     * @param owner authenticated consumer
     * @return whether explicit selection is available in this workspace
     */
    public boolean available(User owner) {
        return configured() && models.list().stream().anyMatch(models::supportsLocalMemory)
            && inPersonalWorkspace(owner);
    }

    Binding admit(User owner, String target, Source source) {
        if (!configured()) {
            throw unavailable();
        }
        if (!inPersonalWorkspace(owner)) {
            throw missing();
        }
        long historyEpoch = historyEpoch(owner);
        if (historyEpoch == 0) {
            throw unavailable();
        }
        var scope = currentScope(owner, target, source.sourceConversationId())
            .orElseThrow(AssistantHistoryReferences::missing);
        if (!sourceExists(owner, source)) {
            throw missing();
        }
        return new Binding(target, source, historyEpoch, scope.source().readRevision(),
            scope.target().readRevision(), scope.source().placementRevision(),
            scope.target().placementRevision());
    }

    void persist(String runId, User owner, Binding binding) {
        jdbc.update("""
            INSERT INTO assistant_run_history_reference
                (run_id, account_id, workspace_id, target_conversation_id, source_conversation_id,
                 source_message_id, history_epoch, source_read_revision, target_read_revision,
                 source_placement_revision, target_placement_revision)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            runId, owner.id(), owner.workspaceId(), binding.target(),
            binding.source().sourceConversationId(),
            binding.source().sourceMessageId(), binding.historyEpoch(),
            binding.sourceReadRevision(),
            binding.targetReadRevision(), binding.sourcePlacementRevision(),
            binding.targetPlacementRevision());
    }

    boolean exists(String runId) {
        return !jdbc
            .queryForList("SELECT run_id FROM assistant_run_history_reference WHERE run_id = ?",
                String.class, runId)
            .isEmpty();
    }

    Optional<Material> material(User owner, String runId) {
        return currentBinding(owner, runId)
            .flatMap(binding -> jdbc.queryForList("SELECT LEFT(m.content, ?) " + SOURCE_REPLY,
                String.class, MAX_POINTS, binding.source().sourceConversationId(),
                binding.source().sourceMessageId(), owner.id(), owner.workspaceId()).stream()
                .filter(text -> !text.isBlank()).findFirst())
            .map(text -> new Material(Kind.HISTORY_REPLY, text));
    }

    boolean mayPublish(User owner, String runId) {
        return currentBinding(owner, runId).filter(binding -> sourceExists(owner, binding.source()))
            .isPresent();
    }

    void used(String runId, long time) {
        jdbc.update("UPDATE assistant_run_history_reference SET used_at = ? WHERE run_id = ?", time,
            runId);
    }

    /**
     * Returns source IDs for a completed reply only while that exact consent remains current.
     *
     * @param actor authenticated owner
     * @param target owning conversation
     * @param reply generated reply ID
     * @return source status and current IDs, without title or excerpt
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Receipt receipt(Principal actor, String target, String reply) {
        if (!uuid(target) || !uuid(reply)) {
            throw invalid();
        }
        User owner = actor.user();
        accounts.lockActive(owner.id());
        readAccess.require(owner.id(), owner.workspaceId(), target);
        var bound = jdbc.query(BINDINGS + """
            JOIN assistant_run r ON r.id = b.run_id
            WHERE b.account_id = ? AND b.workspace_id = ? AND b.target_conversation_id = ?
                AND r.response_message_id = ? AND r.state = 'completed' AND b.used_at IS NOT NULL
            """, BINDING, owner.id(), owner.workspaceId(), target, reply).stream().findFirst()
            .orElseThrow(AssistantHistoryReferences::missing);
        if (!valid(owner, bound) || !sourceExists(owner, bound.source())) {
            return UNAVAILABLE;
        }
        return new Receipt("history_reply", "available", bound.source().sourceConversationId(),
            bound.source().sourceMessageId());
    }

    private Optional<Binding> currentBinding(User owner, String runId) {
        return jdbc.query(BINDINGS + """
            WHERE b.run_id = ? AND b.account_id = ? AND b.workspace_id = ? AND b.used_at IS NULL
            """, BINDING, runId, owner.id(), owner.workspaceId()).stream().findFirst()
            .filter(binding -> valid(owner, binding));
    }

    private boolean valid(User owner, Binding binding) {
        if (!configured() || !inPersonalWorkspace(owner)
            || binding.source().sourceConversationId() == null
            || binding.historyEpoch() != historyEpoch(owner)) {
            return false;
        }
        return currentScope(owner, binding.target(), binding.source().sourceConversationId())
            .filter(
                scope -> Objects.equals(scope.source().readRevision(), binding.sourceReadRevision())
                    && Objects.equals(scope.target().readRevision(), binding.targetReadRevision())
                    && Objects.equals(scope.source().placementRevision(),
                        binding.sourcePlacementRevision())
                    && Objects.equals(scope.target().placementRevision(),
                        binding.targetPlacementRevision()))
            .isPresent();
    }

    private boolean configured() {
        return enabled && journal.acceptsErasure();
    }

    private boolean inPersonalWorkspace(User owner) {
        if (owner == null || journal.blocksAccount(owner.id())) {
            return false;
        }
        return jdbc.query("""
            SELECT m.membership_revision
            FROM assistant_workspace w JOIN assistant_workspace_member m ON m.workspace_id = w.id
            WHERE w.id = ? AND w.personal_owner_id = ? AND m.account_id = ?
            """, (row, index) -> !journal.blocksWorkspaceMembership(owner.id(), owner.workspaceId(),
            row.getString("membership_revision")), owner.workspaceId(), owner.id(), owner.id())
            .stream().findFirst().orElse(false);
    }

    private long historyEpoch(User owner) {
        long cutoff = journal.memoryRevocations(owner.id(), owner.workspaceId(), null).history();
        return jdbc.queryForList("""
            SELECT history_epoch FROM assistant_memory_settings
            WHERE account_id = ? AND workspace_id = ? AND scope_kind = 'personal' AND scope_id = ?
                AND reference_history = TRUE AND history_epoch > ?
            """, Long.class, owner.id(), owner.workspaceId(), owner.workspaceId(), cutoff)
            .stream().findFirst().orElse(0L);
    }

    private Optional<Scope> currentScope(User owner, String target, String source) {
        List<Policy> rows = jdbc.query("""
            SELECT id, read_locked, read_lock_revision FROM assistant_conversation
            WHERE account_id = ? AND workspace_id = ? AND id IN (?, ?)
                AND project_id IS NULL AND deleted_at IS NULL
            """, (row, index) -> new Policy(row.getString("id"), row.getBoolean("read_locked"),
            row.getString("read_lock_revision")), owner.id(), owner.workspaceId(), target, source);
        if (rows.size() != 2) {
            return Optional.empty();
        }
        var open = rows.stream()
            .filter(row -> readAccess.open(owner.id(), owner.workspaceId(), row.id(), row.locked(),
                row.readRevision()))
            .map(row -> new ConversationProof(row.id(), row.readRevision(),
                journal.historyPlacementRevision(owner.id(), owner.workspaceId(), row.id())))
            .toList();
        if (open.size() != 2) {
            return Optional.empty();
        }
        return Optional.of(open.get(0).id().equals(target)
            ? new Scope(open.get(0), open.get(1)) : new Scope(open.get(1), open.get(0)));
    }

    private boolean sourceExists(User owner, Source source) {
        return !jdbc.queryForList("SELECT m.id " + SOURCE_REPLY, String.class,
            source.sourceConversationId(), source.sourceMessageId(), owner.id(),
            owner.workspaceId()).isEmpty();
    }

    static boolean uuid(String value) {
        try {
            return value != null && UUID.fromString(value).toString().equals(value);
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    static AssistantApiException invalid() {
        return new AssistantApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
            "Select one completed assistant reply from a different personal conversation.");
    }

    static AssistantApiException unavailable() {
        return new AssistantApiException(HttpStatus.FORBIDDEN, "CAPABILITY_UNAVAILABLE",
            "Explicit conversation history is unavailable for this request.");
    }

    private static AssistantApiException missing() {
        return new AssistantApiException(HttpStatus.NOT_FOUND, "NOT_FOUND",
            "History reference not found.");
    }

    /**
     * One prior assistant reply selected for this request.
     *
     * @param sourceConversationId owned personal conversation
     * @param sourceMessageId immutable completed assistant reply
     */
    public record Source(String sourceConversationId, String sourceMessageId) {
    }

    /**
     * Authorized immutable reply lineage without source content.
     *
     * @param kind explicit history reply
     * @param status availability under current policy
     * @param sourceConversationId source ID when authorized
     * @param sourceMessageId source reply when authorized
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Receipt(String kind, String status, String sourceConversationId,
        String sourceMessageId) {
    }

    record Binding(String target, Source source, long historyEpoch, String sourceReadRevision,
        String targetReadRevision, String sourcePlacementRevision, String targetPlacementRevision) {
    }

    private record Scope(ConversationProof target, ConversationProof source) {
    }

    private record ConversationProof(String id, String readRevision, String placementRevision) {
    }

    private record Policy(String id, boolean locked, String readRevision) {
    }
}

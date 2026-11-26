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

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import top.continew.admin.assistant.memory.AssistantProjectMemorySources.Access;
import top.continew.admin.assistant.privacy.AssistantDeletionJournal;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Reads current, explicitly authorized memory for a local synthetic generation.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
@Service
@ConditionalOnProperty(name = "assistant.enabled", havingValue = "true")
public class AssistantGenerationMemory {

    private static final int MAX_MATERIALS = 3;
    private static final int MAX_POINTS = 2_000;
    private static final String SETTINGS_SCOPE = " FROM assistant_memory_settings"
        + " WHERE account_id = ? AND workspace_id = ? AND scope_kind = ? AND scope_id = ?";
    private static final String ENTRY_SCOPE = " FROM assistant_memory_entry"
        + " WHERE account_id = ? AND workspace_id = ? AND scope_kind = ? AND scope_id = ?"
        + " AND deleted_at IS NULL";
    private static final Controls DEFAULT = new Controls(false, 0, 0);
    private static final RowMapper<Scope> SCOPE = (row, index) -> new Scope(
        row.getString("account_id"), row.getString("workspace_id"),
        row.getString("conversation_id"), row.getString("project_id"));

    private final JdbcTemplate jdbc;
    private final AssistantDeletionJournal journal;
    private final AssistantProjectMemorySources sources;
    private final boolean enabled;

    /**
     * Configures selection for the independently gated generation path.
     *
     * @param jdbc assistant database
     * @param journal durable revocation policies
     * @param sources scoped project memory provenance
     * @param memoryEnabled manual memory feature flag
     * @param contextEnabled opt-in for generation with synthetic memory context
     */
    public AssistantGenerationMemory(JdbcTemplate jdbc, AssistantDeletionJournal journal,
        AssistantProjectMemorySources sources,
        @Value("${assistant.memory.enabled:false}") boolean memoryEnabled,
        @Value("${assistant.memory.context-enabled:false}") boolean contextEnabled) {
        this.jdbc = jdbc;
        this.journal = journal;
        this.sources = sources;
        this.enabled = memoryEnabled && contextEnabled;
    }

    /**
     * Indicates whether the deployment opted into local personalization.
     *
     * @return true if both memory feature flags are enabled
     */
    public boolean configured() {
        return enabled;
    }

    /**
     * Builds private material after a worker has committed its current lease.
     *
     * @param runId internal run identifier
     * @param leaseToken current worker lease
     * @param room available Unicode code points after retaining the original branch
     * @param slots maximum additional materials allowed by the original turn limit
     * @return selected material and proof, held only in worker memory
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Optional<Selection> select(String runId, String leaseToken, int room, int slots) {
        if (!enabled || !journal.acceptsErasure() || room <= 0 || slots <= 0) {
            return Optional.empty();
        }
        Optional<Scope> candidate = activeScope(runId, leaseToken);
        if (candidate.isEmpty()) {
            return Optional.empty();
        }
        Scope scope = candidate.orElseThrow();
        if (!lockScope(scope) || !stillActive(scope, runId, leaseToken)) {
            return Optional.empty();
        }
        Controls controls = controls(scope);
        var cutoffs = journal.memoryRevocations(scope.accountId(), scope.workspaceId(),
            scope.projectId());
        int limit = Math.min(room, MAX_POINTS);
        int expected = Math.min(slots, MAX_MATERIALS);
        List<Material> material = new ArrayList<>();
        List<String> entryIds = new ArrayList<>();
        int used = 0;
        long instructionsEpoch = 0;
        if (scope.projectId() == null && controls.instructionsEpoch() > cutoffs.instructions()) {
            var instructions = instructions(scope, controls.instructionsEpoch(), limit);
            if (instructions.isPresent()) {
                String text = instructions.orElseThrow();
                material.add(new Material(Kind.INSTRUCTIONS, text));
                instructionsEpoch = controls.instructionsEpoch();
                used += units(text);
            }
        }
        if (material.size() < expected && used < limit && controls.memoryEnabled()
            && controls.memoryEpoch() > cutoffs.memory()) {
            for (String id : candidateIds(scope, limit - used)) {
                if (journal.blocksMemoryEntry(scope.accountId(), scope.workspaceId(), id)) {
                    continue;
                }
                Optional<String> entry = entry(scope, id);
                if (entry.isEmpty() || units(entry.orElseThrow()) > limit - used) {
                    continue;
                }
                String text = entry.orElseThrow();
                material.add(new Material(scope.projectId() == null ? Kind.PERSONAL_MEMORY
                    : Kind.PROJECT_MEMORY, text));
                entryIds.add(id);
                used += units(text);
                if (material.size() == expected || used == limit) {
                    break;
                }
            }
        }
        if (material.isEmpty()) {
            return Optional.empty();
        }
        Access sourceAccess = entryIds.isEmpty() || scope.projectId() == null
            ? null : sources.capture(scope);
        Proof proof = new Proof(scope, instructionsEpoch,
            entryIds.isEmpty() ? 0 : controls.memoryEpoch(), List.copyOf(entryIds), used,
            sourceAccess);
        return Optional.of(new Selection(List.copyOf(material), proof));
    }

    /**
     * Revalidates selected material at dispatch or within the publication transaction.
     *
     * @param runId run being completed
     * @param leaseToken current worker lease
     * @param proof ephemeral proof from the original selection
     * @return whether the caller may publish output based on those inputs
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public boolean lockAndValidate(String runId, String leaseToken, Proof proof) {
        Scope scope = proof.scope();
        if (!enabled || !lockScope(scope) || !stillActive(scope, runId, leaseToken)
            || (proof.sourceAccess() != null && !sources.current(scope, proof.sourceAccess()))) {
            return false;
        }
        Controls controls = controls(scope);
        var cutoffs = journal.memoryRevocations(scope.accountId(), scope.workspaceId(),
            scope.projectId());
        if (proof.instructionsEpoch() > 0
            && (proof.instructionsEpoch() != controls.instructionsEpoch()
                || proof.instructionsEpoch() <= cutoffs.instructions())) {
            return false;
        }
        return proof.entryIds().isEmpty() || (controls.memoryEnabled()
            && proof.memoryEpoch() == controls.memoryEpoch()
            && proof.memoryEpoch() > cutoffs.memory()
            && proof.entryIds().stream().allMatch(
                id -> !journal.blocksMemoryEntry(scope.accountId(), scope.workspaceId(), id)
                    && entryExists(scope, id)));
    }

    /**
     * Saves ID-only receipts in the transaction that successfully completed the reply.
     *
     * @param runId completed run
     * @param proof the exact inputs validated for publication
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordProjectSources(String runId, Proof proof) {
        sources.record(runId, proof);
    }

    private Optional<Scope> activeScope(String runId, String leaseToken) {
        return jdbc.query("""
            SELECT r.account_id, r.workspace_id, r.conversation_id, c.project_id
            FROM assistant_run r
            JOIN assistant_conversation c ON c.id = r.conversation_id
                AND c.account_id = r.account_id AND c.workspace_id = r.workspace_id
            WHERE r.id = ? AND r.lease_token = ? AND r.state = 'running' AND r.lease_until > ?
                AND c.deleted_at IS NULL AND c.read_locked = FALSE
            """, SCOPE, runId, leaseToken, System.currentTimeMillis()).stream()
            .filter(scope -> !journal.conversationReadLocked(scope.accountId(),
                scope.workspaceId(), scope.conversationId()))
            .findFirst();
    }

    private boolean stillActive(Scope scope, String runId, String leaseToken) {
        return journal.acceptsErasure() && !journal.blocksAccount(scope.accountId())
            && activeScope(runId, leaseToken).filter(scope::equals).isPresent();
    }

    private boolean lockScope(Scope scope) {
        if (jdbc
            .queryForList(
                "SELECT id FROM assistant_account WHERE id = ? AND deleted_at IS NULL FOR UPDATE",
                String.class, scope.accountId())
            .isEmpty()
            || jdbc.queryForList("""
                SELECT account_id FROM assistant_workspace_member
                WHERE account_id = ? AND workspace_id = ? FOR UPDATE
                """, String.class, scope.accountId(), scope.workspaceId()).isEmpty()
            || jdbc
                .queryForList(
                    """
                        SELECT id FROM assistant_conversation
                        WHERE id = ? AND account_id = ? AND workspace_id = ?
                            AND deleted_at IS NULL AND read_locked = FALSE FOR UPDATE
                        """,
                    String.class, scope.conversationId(), scope.accountId(), scope.workspaceId())
                .isEmpty()) {
            return false;
        }
        return scope.projectId() == null || !jdbc.queryForList("""
            SELECT id FROM assistant_project
            WHERE id = ? AND account_id = ? AND workspace_id = ? AND deleted_at IS NULL FOR UPDATE
            """, String.class, scope.projectId(), scope.accountId(), scope.workspaceId()).isEmpty();
    }

    private Controls controls(Scope scope) {
        return jdbc
            .query("SELECT memory_enabled, memory_epoch, instructions_epoch" + SETTINGS_SCOPE,
                (row, index) -> new Controls(row.getBoolean("memory_enabled"),
                    row.getLong("memory_epoch"),
                    row.getLong("instructions_epoch")),
                scope.accountId(), scope.workspaceId(), scope.kind(), scope.scopeId())
            .stream().findFirst().orElse(DEFAULT);
    }

    private Optional<String> instructions(Scope scope, long epoch, int room) {
        return jdbc.queryForList("SELECT instructions" + SETTINGS_SCOPE
            + " AND instructions_epoch = ? AND CHAR_LENGTH(instructions) BETWEEN 1 AND ?",
            String.class, scope.accountId(), scope.workspaceId(), scope.kind(), scope.scopeId(),
            epoch,
            room).stream().filter(text -> !text.isBlank() && units(text) <= room).findFirst();
    }

    private List<String> candidateIds(Scope scope, int room) {
        return jdbc.queryForList("SELECT id" + ENTRY_SCOPE
            + " AND CHAR_LENGTH(content) BETWEEN 1 AND ? ORDER BY created_at DESC, id DESC LIMIT 64",
            String.class, scope.accountId(), scope.workspaceId(), scope.kind(), scope.scopeId(),
            room);
    }

    private Optional<String> entry(Scope scope, String id) {
        return jdbc.queryForList("SELECT content" + ENTRY_SCOPE + " AND id = ?", String.class,
            scope.accountId(), scope.workspaceId(), scope.kind(), scope.scopeId(), id)
            .stream().findFirst();
    }

    private boolean entryExists(Scope scope, String id) {
        return !jdbc.queryForList("SELECT id" + ENTRY_SCOPE + " AND id = ?", String.class,
            scope.accountId(), scope.workspaceId(), scope.kind(), scope.scopeId(), id).isEmpty();
    }

    private static int units(String text) {
        return text.codePointCount(0, text.length());
    }

    /**
     * Kind of privately selected material.
     */
    public enum Kind {
        INSTRUCTIONS,
        PERSONAL_MEMORY,
        PROJECT_MEMORY,
        CUSTOM_INSTRUCTIONS,
        CUSTOM_KNOWLEDGE,
        HISTORY_REPLY
    }

    /**
     * One in-memory model input.
     *
     * @param kind purpose and scope of this input
     * @param content authorized text
     */
    public record Material(Kind kind, String content) {

        @Override
        public String toString() {
            return "Material[" + kind + "]";
        }
    }

    /**
     * An in-memory model selection and its transient publication proof.
     *
     * @param material private model input
     * @param proof scoped selection metadata
     */
    public record Selection(List<Material> material, Proof proof) {

        @Override
        public String toString() {
            return "Selection[protected]";
        }
    }

    /**
     * Transient source metadata used for permission revalidation.
     *
     * @param scope selection scope
     * @param instructionsEpoch instruction revision or zero
     * @param memoryEpoch entry authorization revision or zero
     * @param entryIds selected immutable entries
     * @param points source text length in Unicode code points
     * @param sourceAccess permission generations for project source receipts
     */
    public record Proof(Scope scope, long instructionsEpoch, long memoryEpoch,
        List<String> entryIds, int points, Access sourceAccess) {

        @Override
        public String toString() {
            return "Proof[protected]";
        }
    }

    /**
     * Authoritative owner and conversation bound to the leased run.
     *
     * @param accountId current run owner
     * @param workspaceId current run workspace
     * @param conversationId current run conversation
     * @param projectId current conversation project or null
     */
    public record Scope(String accountId, String workspaceId, String conversationId,
        String projectId) {

        private String kind() {
            return projectId == null ? "personal" : "project";
        }

        private String scopeId() {
            return projectId == null ? workspaceId : projectId;
        }
    }

    private record Controls(boolean memoryEnabled, long memoryEpoch, long instructionsEpoch) {
    }
}

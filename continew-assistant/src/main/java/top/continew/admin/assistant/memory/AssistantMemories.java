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
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.continew.admin.assistant.account.AssistantAccounts;
import top.continew.admin.assistant.account.AssistantAccounts.User;
import top.continew.admin.assistant.account.AssistantApiException;
import top.continew.admin.assistant.privacy.AssistantDeletionJournal;
import top.continew.admin.assistant.privacy.AssistantDeletionJournal.MemoryRevocations;
import top.continew.admin.assistant.privacy.AssistantErasure;
import top.continew.admin.assistant.project.AssistantProjects;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Manages explicit memory entries and preferences for one selected scope.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
@Service
@ConditionalOnProperty(name = "assistant.enabled", havingValue = "true")
public class AssistantMemories {

    private static final int MAX_TEXT_POINTS = 2000;
    private static final int MAX_ACTIVE = 64;
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;
    private static final String SCOPED = " WHERE account_id = ? AND workspace_id = ?"
        + " AND scope_kind = ? AND scope_id = ?";
    private static final String ACTIVE = " FROM assistant_memory_entry" + SCOPED
        + " AND deleted_at IS NULL";
    private static final State DEFAULT = new State(false, false, "", 0, 0, 0);
    private static final RowMapper<State> SETTINGS = (row, index) -> new State(row
        .getBoolean("memory_enabled"), row.getBoolean("reference_history"),
        row.getString("instructions"), row.getLong("memory_epoch"), row.getLong("history_epoch"),
        row.getLong("instructions_epoch"));
    private static final RowMapper<Entry> ENTRY = (row, index) -> new Entry(row.getString("id"),
        row.getString("content"), Instant.ofEpochMilli(row.getLong("created_at")));

    private final JdbcTemplate jdbc;
    private final AssistantAccounts accounts;
    private final AssistantProjects projects;
    private final AssistantDeletionJournal journal;
    private final AssistantErasure erasure;
    private final boolean enabled;

    /**
     * Creates the scoped memory service.
     *
     * @param jdbc database operations
     * @param accounts account access and write serialization
     * @param projects project ownership checks
     * @param journal independent durable revocation journal
     * @param erasure transactional journal application
     * @param enabled global feature opt-in
     */
    public AssistantMemories(JdbcTemplate jdbc, AssistantAccounts accounts,
        AssistantProjects projects, AssistantDeletionJournal journal, AssistantErasure erasure,
        @Value("${assistant.memory.enabled:false}") boolean enabled) {
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.projects = projects;
        this.journal = journal;
        this.erasure = erasure;
        this.enabled = enabled;
    }

    /**
     * Reads the current controls, with absent settings treated as off.
     *
     * @param owner authenticated account and workspace
     * @param projectId selected project or null for personal memory
     * @return effective memory controls
     */
    public Settings settings(User owner, String projectId) {
        Scope scope = scope(owner, projectId, false);
        return visible(scope, current(scope).orElse(DEFAULT));
    }

    /**
     * Applies an explicit memory preference patch.
     *
     * @param owner authenticated account and workspace
     * @param projectId selected project or null for personal memory
     * @param patch submitted preference fields
     * @return effective memory controls
     */
    @Transactional
    public Settings update(User owner, String projectId, SettingsPatch patch) {
        if (projectId != null
            && (patch.referenceHistory() != null || patch.instructions() != null)) {
            throw invalid("Only the enabled setting applies to project memory.");
        }
        if (patch.instructions() != null && !bounded(patch.instructions())) {
            throw invalid("Instructions must be valid plain text of at most 2000 characters.");
        }
        Scope scope = scope(owner, projectId, true);
        Optional<State> previous = current(scope);
        State stored = previous.orElse(DEFAULT);
        Settings visible = visible(scope, stored);
        boolean changeMemory = patch.enabled() != null && patch.enabled() != visible.enabled();
        boolean changeHistory = patch.referenceHistory() != null
            && !patch.referenceHistory().equals(visible.referenceHistory());
        boolean changeInstructions = patch.instructions() != null
            && !patch.instructions().equals(visible.instructions());
        if (!changeMemory && !changeHistory && !changeInstructions) {
            return visible;
        }
        if (changeMemory && Boolean.FALSE.equals(patch.enabled())) {
            erasure.disableMemory(owner, projectId, stored.memoryEpoch());
        }
        if (changeHistory && Boolean.FALSE.equals(patch.referenceHistory())) {
            erasure.disableHistoryReference(owner, stored.historyEpoch());
        }
        if (changeInstructions && !stored.instructions().isEmpty()) {
            erasure.clearMemoryInstructions(owner, stored.instructionsEpoch());
        }
        State base = current(scope).orElse(DEFAULT);
        MemoryRevocations cutoffs = cutoffs(scope);
        State next = new State(
            changeMemory ? patch.enabled() : base.enabled(),
            changeHistory ? patch.referenceHistory() : base.referenceHistory(),
            changeInstructions ? patch.instructions() : base.instructions(),
            changeMemory && patch.enabled() ? nextEpoch(base.memoryEpoch(), cutoffs.memory())
                : base.memoryEpoch(),
            changeHistory && patch.referenceHistory() ? nextEpoch(base.historyEpoch(),
                cutoffs.history()) : base.historyEpoch(),
            changeInstructions && !patch.instructions().isEmpty() ? nextEpoch(
                base.instructionsEpoch(), cutoffs.instructions()) : base.instructionsEpoch());
        if (previous.isPresent()) {
            jdbc.update("""
                UPDATE assistant_memory_settings SET memory_enabled = ?, reference_history = ?,
                    instructions = ?, memory_epoch = ?, history_epoch = ?, instructions_epoch = ?
                """ + SCOPED, next.enabled(), next.referenceHistory(), next.instructions(),
                next.memoryEpoch(), next.historyEpoch(), next.instructionsEpoch(),
                scope.accountId(), scope.workspaceId(), scope.kind(), scope.id());
        } else {
            jdbc.update(
                """
                    INSERT INTO assistant_memory_settings
                        (account_id, workspace_id, scope_kind, scope_id, project_id, memory_enabled,
                            reference_history, instructions, memory_epoch, history_epoch, instructions_epoch)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                scope.accountId(), scope.workspaceId(), scope.kind(), scope.id(), scope
                    .projectId(),
                next.enabled(), next.referenceHistory(), next.instructions(),
                next.memoryEpoch(), next.historyEpoch(), next.instructionsEpoch());
        }
        return visible(scope, next);
    }

    /**
     * Stores text manually supplied in an enabled scope.
     *
     * @param owner authenticated account and workspace
     * @param projectId selected project or null for personal memory
     * @param content memory text
     * @return stored entry
     */
    @Transactional
    public Entry create(User owner, String projectId, String content) {
        requireContent(content);
        Scope scope = scope(owner, projectId, true);
        requireScopeEnabled(scope);
        Integer count = jdbc.queryForObject("SELECT COUNT(*)" + ACTIVE, Integer.class,
            scope.accountId(), scope.workspaceId(), scope.kind(), scope.id());
        if (count != null && count >= MAX_ACTIVE) {
            throw new AssistantApiException(HttpStatus.CONFLICT, "CONFLICT",
                "This memory scope has reached its entry limit.");
        }
        return insertEntry(scope, content);
    }

    /**
     * Replaces one accessible entry and durably revokes its retired identifier.
     *
     * @param owner authenticated account and workspace
     * @param projectId selected project or null for personal memory
     * @param entryId original record identifier
     * @param content replacement text
     * @return current entry, with a new identifier if the text changed
     */
    @Transactional
    public Entry replace(User owner, String projectId, String entryId, String content) {
        requireId(entryId);
        requireContent(content);
        Scope scope = scope(owner, projectId, true);
        requireScopeEnabled(scope);
        if (journal.blocksMemoryEntry(scope.accountId(), scope.workspaceId(), entryId)) {
            throw notFound();
        }
        Entry original = storedEntry(scope, entryId);
        if (content.equals(original.content())) {
            return original;
        }
        // Stage the replacement before the irreversible revocation of the old identifier.
        Entry replacement = insertEntry(scope, content);
        erasure.deleteMemory(owner, projectId, entryId);
        return replacement;
    }

    /**
     * Browses entries from exactly one enabled memory scope.
     *
     * @param owner authenticated account and workspace
     * @param projectId selected project or null for personal memory
     * @param limit optional page size
     * @param cursor optional continuation
     * @return accessible entries and next cursor
     */
    public EntryPage list(User owner, String projectId, Integer limit, String cursor) {
        Scope scope = scope(owner, projectId, false);
        int size = limit == null ? DEFAULT_LIMIT : limit;
        if (size < 1 || size > MAX_LIMIT) {
            throw invalid("Memory page size must be between 1 and 100.");
        }
        Cursor after = Cursor.parse(scope, cursor);
        if (!visible(scope, current(scope).orElse(DEFAULT)).enabled()) {
            return new EntryPage(List.of(), null);
        }
        List<Object> arguments = new ArrayList<>(List.of(scope.accountId(), scope.workspaceId(),
            scope.kind(), scope.id()));
        String sql = "SELECT id, content, created_at" + ACTIVE;
        if (after != null) {
            sql += " AND (created_at < ? OR (created_at = ? AND id < ?))";
            arguments.add(after.createdAt());
            arguments.add(after.createdAt());
            arguments.add(after.id());
        }
        List<Entry> entries = jdbc.query(sql + " ORDER BY created_at DESC, id DESC LIMIT "
            + MAX_ACTIVE, ENTRY, arguments.toArray()).stream()
            .filter(entry -> !journal.blocksMemoryEntry(scope.accountId(), scope.workspaceId(),
                entry.id()))
            .toList();
        if (!visible(scope, current(scope).orElse(DEFAULT)).enabled()) {
            return new EntryPage(List.of(), null);
        }
        List<Entry> page = entries.subList(0, Math.min(size, entries.size()));
        String next = entries.size() > size ? Cursor.encode(scope, page.get(size - 1)) : null;
        return new EntryPage(page, next);
    }

    /**
     * Reads an accessible entry from its enabled scope.
     *
     * @param owner authenticated account and workspace
     * @param projectId selected project or null for personal memory
     * @param entryId record identifier
     * @return accessible entry
     */
    public Entry get(User owner, String projectId, String entryId) {
        Scope scope = scope(owner, projectId, false);
        requireId(entryId);
        if (!visible(scope, current(scope).orElse(DEFAULT)).enabled()
            || journal.blocksMemoryEntry(scope.accountId(), scope.workspaceId(), entryId)) {
            throw notFound();
        }
        Entry found = storedEntry(scope, entryId);
        if (!visible(scope, current(scope).orElse(DEFAULT)).enabled()
            || journal.blocksMemoryEntry(scope.accountId(), scope.workspaceId(), entryId)) {
            throw notFound();
        }
        return found;
    }

    /**
     * Durably deletes a known owned entry, including while that scope is off.
     *
     * @param owner authenticated account and workspace
     * @param projectId selected project or null for personal memory
     * @param entryId record identifier
     */
    @Transactional
    public void delete(User owner, String projectId, String entryId) {
        scope(owner, projectId, true);
        requireId(entryId);
        erasure.deleteMemory(owner, projectId, entryId);
    }

    private Entry storedEntry(Scope scope, String entryId) {
        return jdbc.query("SELECT id, content, created_at" + ACTIVE + " AND id = ?", ENTRY,
            scope.accountId(), scope.workspaceId(), scope.kind(), scope.id(), entryId).stream()
            .findFirst().orElseThrow(AssistantMemories::notFound);
    }

    private Entry insertEntry(Scope scope, String content) {
        String id = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        jdbc.update("""
            INSERT INTO assistant_memory_entry
                (id, account_id, workspace_id, scope_kind, scope_id, content, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """, id, scope.accountId(), scope.workspaceId(), scope.kind(), scope.id(), content,
            now);
        return new Entry(id, content, Instant.ofEpochMilli(now));
    }

    private void requireScopeEnabled(Scope scope) {
        if (!visible(scope, current(scope).orElse(DEFAULT)).enabled()) {
            throw new AssistantApiException(HttpStatus.FORBIDDEN, "CAPABILITY_UNAVAILABLE",
                "Memory is off in this scope.");
        }
    }

    private Scope scope(User owner, String projectId, boolean write) {
        requireReady();
        if (write) {
            accounts.lockActive(owner.id());
        }
        String membership = "SELECT account_id FROM assistant_workspace_member "
            + "WHERE account_id = ? AND workspace_id = ?";
        if (jdbc.queryForList(membership + (write ? " FOR UPDATE" : ""), String.class,
            owner.id(), owner.workspaceId()).isEmpty()) {
            throw new AssistantApiException(HttpStatus.FORBIDDEN, "FORBIDDEN",
                "Workspace access is required.");
        }
        if (projectId != null) {
            projects.requireOwned(owner, projectId);
        }
        return new Scope(owner.id(), owner.workspaceId(), projectId);
    }

    private void requireReady() {
        if (!enabled || !journal.enabled()) {
            throw new AssistantApiException(HttpStatus.FORBIDDEN, "CAPABILITY_UNAVAILABLE",
                "Manual memory is unavailable.");
        }
        if (!journal.acceptsErasure()) {
            throw new AssistantApiException(HttpStatus.SERVICE_UNAVAILABLE,
                "CAPABILITY_UNAVAILABLE", "Memory revocation is temporarily unavailable.");
        }
    }

    private Optional<State> current(Scope scope) {
        return jdbc.query("""
            SELECT memory_enabled, reference_history, instructions,
                memory_epoch, history_epoch, instructions_epoch
            FROM assistant_memory_settings
            """ + SCOPED, SETTINGS, scope.accountId(), scope.workspaceId(), scope.kind(),
            scope.id()).stream().findFirst();
    }

    private MemoryRevocations cutoffs(Scope scope) {
        return journal.memoryRevocations(scope.accountId(), scope.workspaceId(), scope.projectId());
    }

    private Settings visible(Scope scope, State state) {
        MemoryRevocations limits = cutoffs(scope);
        boolean memory = state.enabled() && state.memoryEpoch() > limits.memory();
        if (scope.projectId() != null) {
            return new Settings(memory, null, null);
        }
        return new Settings(memory,
            state.referenceHistory() && state.historyEpoch() > limits.history(),
            state.instructionsEpoch() > limits.instructions() ? state.instructions() : "");
    }

    private static long nextEpoch(long current, long cutoff) {
        if (cutoff == Long.MAX_VALUE) {
            throw new AssistantApiException(HttpStatus.SERVICE_UNAVAILABLE,
                "CAPABILITY_UNAVAILABLE", "Memory revocation is temporarily unavailable.");
        }
        return Math.incrementExact(Math.max(System.currentTimeMillis(), Math.max(current, cutoff)));
    }

    private static boolean bounded(String value) {
        return value.length() <= MAX_TEXT_POINTS * 2
            && value.codePointCount(0, value.length()) <= MAX_TEXT_POINTS
            && StandardCharsets.UTF_8.newEncoder().canEncode(value)
            && value.codePoints().noneMatch(point -> Character.isISOControl(point)
                && point != '\n' && point != '\r' && point != '\t');
    }

    private static void requireContent(String content) {
        if (content == null || content.isBlank() || !bounded(content)) {
            throw invalid("Memory must contain valid plain text of at most 2000 characters.");
        }
    }

    private static void requireId(String id) {
        try {
            if (id != null && id.length() == 36 && UUID.fromString(id).toString().equals(id)) {
                return;
            }
        } catch (IllegalArgumentException ignored) {
            // The same validation error applies to malformed and missing identifiers.
        }
        throw invalid("Invalid memory identifier.");
    }

    private static AssistantApiException invalid(String message) {
        return new AssistantApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message);
    }

    private static AssistantApiException notFound() {
        return new AssistantApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Memory not found.");
    }

    /**
     * Current scope controls.
     *
     * @param enabled whether manual memory is on
     * @param referenceHistory optional personal history preference
     * @param instructions optional personal custom instructions
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Settings(boolean enabled, Boolean referenceHistory, String instructions) {

        @Override
        public String toString() {
            return "Settings[protected]";
        }
    }

    /**
     * Explicitly submitted memory controls.
     *
     * @param enabled optional manual memory setting
     * @param referenceHistory optional personal history setting
     * @param instructions optional personal custom instructions
     */
    public record SettingsPatch(Boolean enabled, Boolean referenceHistory, String instructions) {

        @Override
        public String toString() {
            return "SettingsPatch[protected]";
        }
    }

    /**
     * Text deliberately saved by the memory owner.
     *
     * @param id record identifier
     * @param content original plain text
     * @param createdAt creation instant
     */
    public record Entry(String id, String content, Instant createdAt) {

        @Override
        public String toString() {
            return "Entry[protected]";
        }
    }

    /**
     * Page of entries from one memory scope.
     *
     * @param items accessible entries
     * @param nextCursor continuation or null at the end
     */
    public record EntryPage(List<Entry> items,
        @JsonInclude(JsonInclude.Include.ALWAYS) String nextCursor) {
    }

    private record Scope(String accountId, String workspaceId, String projectId) {

        private String kind() {
            return projectId == null ? "personal" : "project";
        }

        private String id() {
            return projectId == null ? workspaceId : projectId;
        }

        private String cursorPrefix() {
            return accountId + ":" + workspaceId + ":" + kind() + ":" + id() + ":";
        }
    }

    private record State(boolean enabled, boolean referenceHistory, String instructions,
        long memoryEpoch, long historyEpoch, long instructionsEpoch) {

        @Override
        public String toString() {
            return "State[protected]";
        }
    }

    private record Cursor(long createdAt, String id) {

        private static Cursor parse(Scope scope, String encoded) {
            if (encoded == null) {
                return null;
            }
            if (encoded.length() > 256) {
                throw invalid("Invalid memory cursor.");
            }
            try {
                String decoded = new String(Base64.getUrlDecoder().decode(encoded),
                    StandardCharsets.US_ASCII);
                String prefix = scope.cursorPrefix();
                if (decoded.startsWith(prefix)) {
                    String[] parts = decoded.substring(prefix.length()).split(":", -1);
                    if (parts.length == 2) {
                        long timestamp = Long.parseLong(parts[0]);
                        requireId(parts[1]);
                        if (timestamp >= 0) {
                            return new Cursor(timestamp, parts[1]);
                        }
                    }
                }
            } catch (IllegalArgumentException ignored) {
                // Invalid cursors all receive the same response.
            }
            throw invalid("Invalid memory cursor.");
        }

        private static String encode(Scope scope, Entry entry) {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                (scope.cursorPrefix() + entry.createdAt().toEpochMilli() + ":" + entry.id())
                    .getBytes(StandardCharsets.US_ASCII));
        }
    }
}

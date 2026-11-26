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

package top.continew.admin.assistant.research;

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
import top.continew.admin.assistant.account.AssistantAccounts.User;
import top.continew.admin.assistant.account.AssistantApiException;
import top.continew.admin.assistant.privacy.AssistantDeletionJournal;
import top.continew.admin.assistant.privacy.AssistantErasure;
import top.continew.admin.assistant.retrieval.AssistantRetrieval;
import top.continew.admin.assistant.retrieval.AssistantRetrieval.ResearchEvidence;
import top.continew.admin.assistant.retrieval.AssistantRetrieval.ResearchSource;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Stores scoped plans and processes approved local evidence in bounded checkpoints.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
@Service
@ConditionalOnProperty(name = {"assistant.enabled", "assistant.research.enabled",
    "assistant.retrieval.enabled"}, havingValue = "true")
public class AssistantResearch {

    private static final int MAX_SOURCES = 6;
    private static final int MAX_REVISIONS = 16;
    private static final int MAX_ACTIVE = 8;
    private static final int MAX_PAGE = 50;
    private static final String DRAFT = "awaiting_approval";
    private static final String QUEUED = "queued";
    private static final String RUNNING = "running";
    private static final String COMPLETED = "completed";
    private static final String CANCELLED = "cancelled";
    private static final String FAILED = "failed";
    private static final String SOURCES_CHANGED = "SOURCES_CHANGED";
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9._~-]{1,128}");
    private static final String MEMBERSHIP =
        """
            SELECT workspace_id FROM assistant_workspace_member WHERE account_id = ? AND workspace_id = ?
            """;
    private static final RowMapper<StoredTask> ROW = (row, index) -> new StoredTask(
        row.getString("id"), row.getString("account_id"), row.getString("workspace_id"),
        row.getInt("revision"), row.getString("state"), row.getInt("progress"),
        row.getLong("approval_epoch"), row.getLong("due_at"), row.getString("error_code"),
        row.getLong("created_at"), row.getLong("updated_at"));

    private final JdbcTemplate jdbc;
    private final AssistantAccounts accounts;
    private final AssistantRetrieval catalogue;
    private final AssistantDeletionJournal journal;
    private final AssistantErasure erasure;
    private final long checkpointDelay;

    /**
     * Wires scoped persistence, approved local evidence and durable revocation.
     *
     * @param jdbc assistant database
     * @param accounts account lifecycle guard
     * @param catalogue approved offline evidence
     * @param journal independent erasure and approval cutoff journal
     * @param erasure transactional revocation and replay
     * @param checkpointDelay checkpoint delay in milliseconds
     */
    public AssistantResearch(JdbcTemplate jdbc, AssistantAccounts accounts,
        AssistantRetrieval catalogue, AssistantDeletionJournal journal, AssistantErasure erasure,
        @Value("${assistant.research.checkpoint-delay-ms:250}") long checkpointDelay) {
        if (checkpointDelay < 0 || checkpointDelay > 60_000) {
            throw new IllegalArgumentException("Invalid research checkpoint delay.");
        }
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.catalogue = catalogue;
        this.journal = journal;
        this.erasure = erasure;
        this.checkpointDelay = checkpointDelay;
    }

    /**
     * Lists reviewable curated metadata for the authenticated user.
     *
     * @param user authenticated selected workspace
     * @param cursor prior source
     * @param limit requested number of results
     * @return visible sources
     */
    public Page<ResearchSource> sources(User user, String cursor, int limit) {
        access(user, false);
        requirePageSize(limit);
        if (cursor != null) {
            requireId(cursor);
        }
        List<ResearchSource> rows = catalogue.researchCatalog(cursor == null ? "" : cursor,
            limit + 1);
        List<ResearchSource> visible = rows.subList(0, Math.min(rows.size(), limit));
        verifyRead(user);
        return new Page<>(visible, rows.size() > limit ? visible.get(limit - 1).id() : null);
    }

    /**
     * Persists an unapproved version and allows idempotent creation retries.
     *
     * @param user authenticated research owner
     * @param key create idempotency key
     * @param question research question
     * @param sourceIds exact curated scope selected by the owner
     * @return current task snapshot
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Task create(User user, String key, String question, List<String> sourceIds) {
        String prompt = validatePlan(question, sourceIds);
        if (key == null || !KEY.matcher(key).matches()) {
            throw invalid("A valid Idempotency-Key is required.");
        }
        access(user, true);
        String operation = hash(key);
        Optional<StoredTask> existing = jdbc.query("""
            SELECT * FROM assistant_research_task WHERE account_id = ? AND idempotency_digest = ?
            """, ROW, user.id(), operation).stream().findFirst();
        if (existing.isPresent()) {
            StoredTask stored = existing.orElseThrow();
            if (!stored.workspace().equals(user.workspaceId())
                || !draft(stored.id(), 1).sameInput(prompt, sourceIds)) {
                throw conflict("The idempotency key belongs to another research plan.");
            }
            return snapshot(stored);
        }
        requireCapacity(user.id());
        List<Reference> refs = references(sourceIds);
        String id = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        jdbc.update("""
            INSERT INTO assistant_research_task
                (id, workspace_id, account_id, idempotency_digest, revision, state, progress,
                created_at, updated_at)
            VALUES (?, ?, ?, ?, 1, 'awaiting_approval', 0, ?, ?)
            """, id, user.workspaceId(), user.id(), operation, now, now);
        insertDraft(id, 1, prompt, refs);
        return snapshot(scoped(user, id, false));
    }

    /**
     * Browses private tasks.
     *
     * @param user authenticated research owner
     * @param cursor previous page position
     * @param limit requested number of results
     * @return visible task snapshots
     */
    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public Page<Task> list(User user, String cursor, int limit) {
        access(user, false);
        requirePageSize(limit);
        Cursor after = Cursor.parse(cursor);
        List<StoredTask> rows = jdbc.query("""
            SELECT * FROM assistant_research_task
            WHERE account_id = ? AND workspace_id = ?
                AND (created_at < ? OR (created_at = ? AND id < ?))
            ORDER BY created_at DESC, id DESC LIMIT ?
            """, ROW, user.id(), user.workspaceId(), after.time(), after.time(), after.id(),
            limit + 1);
        List<Task> visible = rows.subList(0, Math.min(rows.size(), limit)).stream()
            .map(this::snapshot).toList();
        verifyRead(user);
        return new Page<>(visible, rows.size() > limit ? Cursor.encode(rows.get(limit - 1)) : null);
    }

    /**
     * Reads the current version of one private task.
     *
     * @param user authenticated research owner
     * @param id task identifier
     * @return current task
     */
    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public Task get(User user, String id) {
        access(user, false);
        Task response = snapshot(scoped(user, id, false));
        verifyRead(user);
        return response;
    }

    /**
     * Stores a distinct version and retires any approval for the prior version.
     *
     * @param user authenticated research owner
     * @param id task identifier
     * @param expected current revision known by the client
     * @param question new research question
     * @param sourceIds explicit curated scope
     * @return current task after the revision
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Task revise(User user, String id, int expected, String question,
        List<String> sourceIds) {
        String prompt = validatePlan(question, sourceIds);
        access(user, true);
        StoredTask stored = scoped(user, id, true);
        requireRevision(stored, expected);
        List<Reference> refs = references(sourceIds);
        if (draft(id, expected).samePlan(prompt, refs)) {
            return snapshot(stored);
        }
        if (expected >= MAX_REVISIONS) {
            throw conflict("This task has reached its revision limit.");
        }
        if (!DRAFT.equals(stored.state()) && !stored.active()) {
            requireCapacity(user.id());
        }
        retire(stored);
        insertDraft(id, expected + 1, prompt, reuseVerified(id, expected, refs));
        jdbc.update("""
            UPDATE assistant_research_task SET revision = ?, state = 'awaiting_approval',
                progress = 0, approval_epoch = NULL, due_at = NULL, error_code = NULL,
                updated_at = ?
            WHERE id = ?
            """, expected + 1, System.currentTimeMillis(), id);
        return snapshot(scoped(user, id, false));
    }

    /**
     * Accepts one owner approval for reviewed current sources and schedules work.
     *
     * @param user authenticated research owner
     * @param id task identifier
     * @param expected reviewed revision
     * @return queued or already advanced version
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Task approve(User user, String id, int expected) {
        access(user, true);
        StoredTask stored = scoped(user, id, true);
        requireRevision(stored, expected);
        if (approved(stored)) {
            requireCurrent(draft(id, expected));
            return snapshot(stored);
        }
        if (!DRAFT.equals(stored.state())) {
            throw conflict("Review a new revision before approving this task.");
        }
        requireCurrent(draft(id, expected));
        long now = System.currentTimeMillis();
        long floor = cutoff(stored);
        if (floor == Long.MAX_VALUE) {
            throw unavailable();
        }
        long epoch = Math.incrementExact(Math.max(now, floor));
        jdbc.update("""
            UPDATE assistant_research_task SET state = 'queued', approval_epoch = ?, due_at = ?,
                updated_at = ? WHERE id = ?
            """, epoch, now, now, id);
        return snapshot(scoped(user, id, false));
    }

    /**
     * Cancels the current version and makes any prior approval unusable after restore.
     *
     * @param user authenticated research owner
     * @param id task identifier
     * @param expected current revision known by the client
     * @return cancelled task
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Task cancel(User user, String id, int expected) {
        access(user, true);
        StoredTask stored = scoped(user, id, true);
        requireRevision(stored, expected);
        retire(stored);
        if (!CANCELLED.equals(stored.state())) {
            finish(id, CANCELLED, null);
        }
        return snapshot(scoped(user, id, false));
    }

    /**
     * Downloads plain text grounded in the exact sources approved for this revision.
     *
     * @param user authenticated selected workspace
     * @param id research task identifier
     * @param expected optional explicit artifact revision
     * @return UTF-8 report bytes
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public byte[] report(User user, String id, Integer expected) {
        access(user, true);
        StoredTask stored = scoped(user, id, true);
        if (expected != null) {
            requireRevision(stored, expected);
        }
        if (!COMPLETED.equals(stored.state()) || !approved(stored)) {
            throw conflict("An approved report is not available for this revision.");
        }
        Draft current = draft(id, stored.revision());
        List<ResearchEvidence> evidence = requireCurrent(current);
        StringBuilder result = new StringBuilder("本地审核来源研究报告\n\n研究问题\n");
        appendText(result, current.question());
        result.append("\n\n已确认的来源与证据\n");
        for (int index = 0; index < evidence.size(); index++) {
            ResearchEvidence item = evidence.get(index);
            result.append('\n').append(index + 1).append(". ");
            appendText(result, item.source().title());
            result.append("\n来源：").append(item.source().url()).append("\n核查时间：")
                .append(item.source().accessedAt()).append("\n审核结论：");
            appendText(result, item.claim());
            result.append("\n证据摘录：");
            appendText(result, item.excerpt());
            result.append('\n');
        }
        verifyRead(user);
        if (!approved(stored)) {
            throw conflict("This research approval has been revoked.");
        }
        return result.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Advances one persisted approval by a single short database-only checkpoint.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void advanceOne() {
        if (journal.dispatchPaused() || !journal.acceptsErasure()) {
            return;
        }
        long now = System.currentTimeMillis();
        List<StoredTask> due = jdbc.query("""
            SELECT * FROM assistant_research_task
            WHERE state IN ('queued', 'running') AND due_at <= ?
            ORDER BY due_at, id LIMIT 8
            """, ROW, now);
        for (StoredTask candidate : due) {
            if (journal.blocksAccount(candidate.owner()) || jdbc.queryForList("""
                SELECT id FROM assistant_account WHERE id = ? AND deleted_at IS NULL
                FOR UPDATE SKIP LOCKED
                """, String.class, candidate.owner()).isEmpty()) {
                continue;
            }
            boolean member = membership(candidate.owner(), candidate.workspace(), true);
            Optional<StoredTask> current = read(candidate.owner(), candidate.workspace(),
                candidate.id(), true);
            if (current.isEmpty() || !current.orElseThrow().active()
                || current.orElseThrow().dueAt() > now) {
                continue;
            }
            StoredTask task = current.orElseThrow();
            if (!member || !approved(task)) {
                retire(task);
                finish(task.id(), CANCELLED, null);
            } else {
                advance(task, now);
            }
            return;
        }
    }

    private void advance(StoredTask task, long now) {
        Draft plan = draft(task.id(), task.revision());
        boolean queued = QUEUED.equals(task.state());
        List<Reference> unverified = plan.references().stream()
            .filter(ref -> ref.verifiedRevision() == 0).toList();
        List<Reference> required = queued ? unverified : plan.references();
        if (plan.references().isEmpty() || !required.isEmpty() && !matches(required)) {
            retire(task);
            finish(task.id(), FAILED, SOURCES_CHANGED);
            return;
        }
        if (!unverified.isEmpty()) {
            jdbc.update("""
                UPDATE assistant_research_source
                SET verified_revision = revision, verified_at = ?
                WHERE task_id = ? AND revision = ? AND verified_revision IS NULL
                """, now, task.id(), task.revision());
        }
        if (queued) {
            jdbc.update("""
                UPDATE assistant_research_task SET state = 'running', progress = 50,
                    due_at = ?, updated_at = ? WHERE id = ?
                """, now + checkpointDelay, now, task.id());
        } else {
            jdbc.update("""
                UPDATE assistant_research_task SET state = 'completed', progress = 100,
                    due_at = NULL, updated_at = ? WHERE id = ?
                """, now, task.id());
        }
    }

    private void access(User user, boolean lock) {
        if (!journal.enabled()) {
            throw new AssistantApiException(HttpStatus.FORBIDDEN, "CAPABILITY_UNAVAILABLE",
                "Local research is unavailable.");
        }
        if (!journal.acceptsErasure()) {
            throw unavailable();
        }
        if (lock) {
            accounts.lockActive(user.id());
        }
        verifyRead(user, lock);
    }

    private void verifyRead(User user) {
        verifyRead(user, false);
    }

    private void verifyRead(User user, boolean lock) {
        if (!membership(user.id(), user.workspaceId(), lock)) {
            throw new AssistantApiException(HttpStatus.FORBIDDEN, "FORBIDDEN",
                "Workspace is unavailable.");
        }
        if (journal.blocksAccount(user.id())) {
            throw missing();
        }
    }

    private boolean membership(String owner, String workspace, boolean lock) {
        return !jdbc.queryForList(MEMBERSHIP + (lock ? " FOR UPDATE" : ""), String.class, owner,
            workspace).isEmpty();
    }

    private StoredTask scoped(User user, String id, boolean lock) {
        requireId(id);
        return read(user.id(), user.workspaceId(), id, lock)
            .orElseThrow(AssistantResearch::missing);
    }

    private Optional<StoredTask> read(String owner, String workspace, String id, boolean lock) {
        return jdbc.query("""
            SELECT * FROM assistant_research_task
            WHERE id = ? AND account_id = ? AND workspace_id = ?
            """ + (lock ? " FOR UPDATE" : ""), ROW, id, owner, workspace).stream().findFirst();
    }

    private Task snapshot(StoredTask stored) {
        Draft plan = draft(stored.id(), stored.revision());
        boolean usable = approved(stored);
        boolean revoked = stored.approval() > 0 && !usable;
        String state = revoked ? CANCELLED : stored.state();
        boolean ready = COMPLETED.equals(state) && usable && matches(plan);
        return new Task(stored.id(), stored.revision(), state, revoked ? 0 : stored.progress(),
            plan.question(), plan.ids(), Instant.ofEpochMilli(stored.createdAt()),
            Instant.ofEpochMilli(stored.updatedAt()), ready, plan.reusedCount(stored.revision()),
            stored.error());
    }

    private Draft draft(String id, int revision) {
        String question = jdbc.queryForObject("""
            SELECT question FROM assistant_research_revision WHERE task_id = ? AND revision = ?
            """, String.class, id, revision);
        List<Reference> references = jdbc.query("""
            SELECT source_id, fingerprint, verified_revision, verified_at
            FROM assistant_research_source
            WHERE task_id = ? AND revision = ? ORDER BY ordinal
            """, AssistantResearch::readReference, id, revision);
        return new Draft(Objects.requireNonNull(question), List.copyOf(references));
    }

    private static Reference readReference(ResultSet row, int index) throws SQLException {
        return new Reference(row.getString("source_id"), row.getString("fingerprint"),
            row.getInt("verified_revision"), row.getLong("verified_at"));
    }

    private List<Reference> reuseVerified(String id, int previousRevision, List<Reference> refs) {
        List<Reference> available = jdbc.query("""
            SELECT source_id, fingerprint, verified_revision, verified_at
            FROM assistant_research_source
            WHERE task_id = ? AND revision <= ? AND verified_revision IS NOT NULL
            ORDER BY revision DESC, ordinal
            """, AssistantResearch::readReference, id, previousRevision);
        Map<SourceVersion, Reference> verified = new LinkedHashMap<>();
        available.forEach(ref -> verified.putIfAbsent(ref.version(), ref));
        return refs.stream().map(ref -> verified.getOrDefault(ref.version(), ref)).toList();
    }

    private void insertDraft(String id, int revision, String question, List<Reference> refs) {
        jdbc.update("""
            INSERT INTO assistant_research_revision (task_id, revision, question) VALUES (?, ?, ?)
            """, id, revision, question);
        for (int index = 0; index < refs.size(); index++) {
            Reference ref = refs.get(index);
            jdbc.update(
                """
                    INSERT INTO assistant_research_source
                        (task_id, revision, ordinal, source_id, fingerprint, verified_revision, verified_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """,
                id, revision, index, ref.id(), ref.fingerprint(),
                ref.verifiedRevision() == 0 ? null : ref.verifiedRevision(),
                ref.verifiedAt() == 0 ? null : ref.verifiedAt());
        }
    }

    private List<Reference> references(List<String> ids) {
        Map<String, ResearchEvidence> available = evidenceById(ids);
        if (available.size() != ids.size()) {
            throw conflict("Review the available catalogue sources before approving this plan.");
        }
        return ids.stream().map(id -> new Reference(id, fingerprint(available.get(id)), 0, 0))
            .toList();
    }

    private List<ResearchEvidence> requireCurrent(Draft plan) {
        Map<String, ResearchEvidence> available = evidenceById(plan.ids());
        if (!matches(plan.references(), available)) {
            throw conflict("Approved sources have changed. Review a new plan revision.");
        }
        return plan.ids().stream().map(available::get).toList();
    }

    private boolean matches(Draft plan) {
        return matches(plan.references());
    }

    private boolean matches(List<Reference> refs) {
        return matches(refs, evidenceById(refs.stream().map(Reference::id).toList()));
    }

    private static boolean matches(List<Reference> refs, Map<String, ResearchEvidence> available) {
        return !refs.isEmpty() && available.size() == refs.size()
            && refs.stream().allMatch(ref -> available.containsKey(ref.id())
                && ref.fingerprint().equals(fingerprint(available.get(ref.id()))));
    }

    private Map<String, ResearchEvidence> evidenceById(List<String> ids) {
        Map<String, ResearchEvidence> available = new LinkedHashMap<>();
        if (!ids.isEmpty()) {
            catalogue.researchEvidence(ids)
                .forEach(item -> available.put(item.source().id(), item));
        }
        return available;
    }

    private static String fingerprint(ResearchEvidence item) {
        var source = item.source();
        return hash(source.id(), source.title(), source.url(), source.accessedAt().toString(),
            item.expiresAt().toString(), item.claim(), item.excerpt());
    }

    private static String hash(String... fields) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String field : fields) {
                byte[] value = field.getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(value.length).array());
                digest.update(value);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable.", failure);
        }
    }

    private long cutoff(StoredTask stored) {
        return journal.researchCutoff(stored.owner(), stored.workspace(), stored.id());
    }

    private boolean approved(StoredTask stored) {
        return stored.approval() > cutoff(stored);
    }

    private void retire(StoredTask stored) {
        if (approved(stored)) {
            erasure.revokeResearch(stored.owner(), stored.workspace(), stored.id(),
                stored.approval());
        }
    }

    private void finish(String id, String state, String error) {
        jdbc.update("""
            UPDATE assistant_research_task SET state = ?, progress = 0, approval_epoch = NULL,
                due_at = NULL, error_code = ?, updated_at = ?
            WHERE id = ?
            """, state, error, System.currentTimeMillis(), id);
    }

    private void requireCapacity(String owner) {
        Integer active = jdbc.queryForObject("""
            SELECT COUNT(*) FROM assistant_research_task
            WHERE account_id = ? AND state IN ('awaiting_approval', 'queued', 'running')
            """, Integer.class, owner);
        if (active != null && active >= MAX_ACTIVE) {
            throw new AssistantApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED",
                "There are too many active research plans.");
        }
    }

    private static void appendText(StringBuilder destination, String value) {
        value.codePoints().filter(point -> !Character.isISOControl(point) || point == '\n'
            || point == '\t').forEach(destination::appendCodePoint);
    }

    private static String validatePlan(String question, List<String> sources) {
        if (question == null || question.length() > 512 || question.isBlank()
            || question.codePointCount(0, question.length()) > 256
            || !StandardCharsets.UTF_8.newEncoder().canEncode(question)
            || question.codePoints()
                .anyMatch(point -> Character.isISOControl(point) && point != '\n'
                    && point != '\r' && point != '\t')
            || sources == null || sources.isEmpty() || sources.size() > MAX_SOURCES
            || sources.stream().distinct().count() != sources.size()) {
            throw invalid(
                "Provide a bounded research question and one to six distinct source IDs.");
        }
        sources.forEach(AssistantResearch::requireId);
        return question.strip();
    }

    private static void requireId(String id) {
        try {
            if (id != null && id.length() == 36 && UUID.fromString(id).toString().equals(id)) {
                return;
            }
        } catch (IllegalArgumentException ignored) {
            // Malformed identifiers use the common validation response.
        }
        throw invalid("Invalid research identifier.");
    }

    private static void requirePageSize(int limit) {
        if (limit < 1 || limit > MAX_PAGE) {
            throw invalid("Page size must be between 1 and 50.");
        }
    }

    private static void requireRevision(StoredTask stored, int expected) {
        if (expected < 1 || expected > MAX_REVISIONS) {
            throw invalid("Invalid research revision.");
        }
        if (stored.revision() != expected) {
            throw conflict("This research plan has changed. Review its current revision.");
        }
    }

    private static AssistantApiException unavailable() {
        return new AssistantApiException(HttpStatus.SERVICE_UNAVAILABLE, "CAPABILITY_UNAVAILABLE",
            "Research approval revocation is temporarily unavailable.");
    }

    private static AssistantApiException missing() {
        return new AssistantApiException(HttpStatus.NOT_FOUND, "NOT_FOUND",
            "Research task not found.");
    }

    private static AssistantApiException invalid(String detail) {
        return new AssistantApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", detail);
    }

    private static AssistantApiException conflict(String detail) {
        return new AssistantApiException(HttpStatus.CONFLICT, "CONFLICT", detail);
    }

    /**
     * Current owner-visible version of a local research plan.
     *
     * @param id task ID
     * @param revision current review number
     * @param state approval and worker state
     * @param progress checkpoint progress
     * @param question current question
     * @param sourceIds exact reviewed source set in display order
     * @param createdAt task creation time
     * @param updatedAt latest task transition time
     * @param reportAvailable whether the currently completed report remains valid
     * @param reusedSourceCount verified sources reused from earlier revisions of this task
     * @param errorCode optional public error classification
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Task(String id, int revision, String state, int progress, String question,
        List<String> sourceIds, Instant createdAt, Instant updatedAt, boolean reportAvailable,
        int reusedSourceCount, String errorCode) {

        @Override
        public String toString() {
            return "Task[protected]";
        }
    }

    /**
     * Bounded result page.
     *
     * @param items current visible records
     * @param nextCursor next page position or null
     * @param <T> record type
     */
    public record Page<T>(List<T> items,
        @JsonInclude(JsonInclude.Include.ALWAYS) String nextCursor) {
    }

    private record StoredTask(String id, String owner, String workspace, int revision, String state,
        int progress, long approval, long dueAt, String error, long createdAt, long updatedAt) {

        private boolean active() {
            return QUEUED.equals(state) || RUNNING.equals(state);
        }
    }

    private record SourceVersion(String id, String fingerprint) {
    }

    private record Reference(String id, String fingerprint, int verifiedRevision, long verifiedAt) {

        private SourceVersion version() {
            return new SourceVersion(id, fingerprint);
        }
    }

    private record Draft(String question, List<Reference> references) {

        private List<String> ids() {
            return references.stream().map(Reference::id).toList();
        }

        private boolean sameInput(String candidate, List<String> requested) {
            return question.equals(candidate) && ids().equals(requested);
        }

        private boolean samePlan(String candidate, List<Reference> requested) {
            return question.equals(candidate)
                && references.stream().map(Reference::version).toList()
                    .equals(requested.stream().map(Reference::version).toList());
        }

        private int reusedCount(int revision) {
            return (int) references.stream().filter(ref -> ref.verifiedRevision() > 0
                && ref.verifiedRevision() < revision).count();
        }

        @Override
        public String toString() {
            return "Draft[protected]";
        }
    }

    private record Cursor(long time, String id) {

        static Cursor parse(String token) {
            if (token == null) {
                return new Cursor(Long.MAX_VALUE, "");
            }
            if (token.length() <= 80) {
                try {
                    String value = new String(Base64.getUrlDecoder().decode(token),
                        StandardCharsets.US_ASCII);
                    String[] fields = value.split(":", -1);
                    if (fields.length == 2) {
                        requireId(fields[1]);
                        long time = Long.parseLong(fields[0]);
                        if (time >= 0) {
                            return new Cursor(time, fields[1]);
                        }
                    }
                } catch (IllegalArgumentException ignored) {
                    // Malformed cursors use the common validation response.
                }
            }
            throw invalid("Invalid research cursor.");
        }

        static String encode(StoredTask task) {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                (task.createdAt() + ":" + task.id()).getBytes(StandardCharsets.US_ASCII));
        }
    }
}

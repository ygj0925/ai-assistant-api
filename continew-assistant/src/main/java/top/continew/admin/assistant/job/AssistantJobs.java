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

package top.continew.admin.assistant.job;

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
import top.continew.admin.assistant.account.AssistantApiException;
import top.continew.admin.assistant.chat.AssistantConversationReadAccess;
import top.continew.admin.assistant.privacy.AssistantDeletionJournal;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Persists background jobs and produces an immutable export of the selected message ancestry.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
@Service
@ConditionalOnProperty(name = {"assistant.enabled", "assistant.jobs.enabled"}, havingValue = "true")
public class AssistantJobs {

    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9._~-]{1,128}");
    private static final int MESSAGE_LIMIT = 256;
    private static final int ACTIVE_LIMIT = 8;
    private static final int SOURCE_LENGTH_LIMIT = 16_000;
    private static final int ARTIFACT_LIMIT = 2 * 1024 * 1024;
    private static final String QUEUED = "queued";
    private static final String RUNNING = "running";
    private static final String COMPLETED = "completed";
    private static final String CANCELLED = "cancelled";
    private static final String FAILED = "failed";
    private static final String EXPORT = "conversation_export";
    private static final String CURRENT_MEMBERSHIP = """
        AND EXISTS (SELECT 1 FROM assistant_workspace_member wm
            WHERE wm.workspace_id = c.workspace_id AND wm.account_id = c.account_id)
        """;
    private static final String ACTIVE_SOURCE =
        """
            SELECT c.id FROM assistant_conversation c
            WHERE c.id = ? AND c.account_id = ? AND c.workspace_id = ? AND c.deleted_at IS NULL AND c.read_locked = FALSE
            """
            + CURRENT_MEMBERSHIP;
    private static final String VISIBLE_CONVERSATION = """
        AND EXISTS (SELECT 1 FROM assistant_conversation c
            WHERE c.id = j.conversation_id AND c.workspace_id = j.workspace_id
                AND c.account_id = j.account_id AND c.deleted_at IS NULL
        """ + CURRENT_MEMBERSHIP + ")";
    private static final String SCOPED_JOB = """
        SELECT j.* FROM assistant_job j
        WHERE j.id = ? AND j.account_id = ? AND j.workspace_id = ?
        """ + VISIBLE_CONVERSATION;
    private static final RowMapper<StoredJob> JOB = (row, index) -> new StoredJob(
        row.getString("id"), row.getString("workspace_id"), row.getString("account_id"),
        row.getString("conversation_id"), row.getString("source_message_id"),
        row.getString("status"), row.getInt("progress"), row.getString("artifact_id"),
        row.getString("artifact_digest"), row.getString("error_code"), row.getLong("created_at"),
        row.getLong("updated_at"));

    private final JdbcTemplate jdbc;
    private final AssistantAccounts accounts;
    private final AssistantDeletionJournal deletions;
    private final AssistantConversationReadAccess readAccess;
    private final long checkpointDelay;

    /**
     * Creates the worker state store.
     *
     * @param jdbc application database
     * @param accounts authenticated account lock ordering
     * @param deletions online and restore privacy fencing
     * @param readAccess current read authorization for the source conversation
     * @param checkpointDelay delay after the preparation checkpoint in milliseconds
     */
    public AssistantJobs(JdbcTemplate jdbc, AssistantAccounts accounts,
        AssistantDeletionJournal deletions, AssistantConversationReadAccess readAccess,
        @Value("${assistant.jobs.checkpoint-delay-ms:250}") long checkpointDelay) {
        if (checkpointDelay < 0 || checkpointDelay > 60_000) {
            throw new IllegalArgumentException("Invalid assistant job checkpoint delay.");
        }
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.deletions = deletions;
        this.readAccess = readAccess;
        this.checkpointDelay = checkpointDelay;
    }

    /**
     * Creates one job and its delivery record in the same transaction.
     *
     * @param principal authenticated account and workspace
     * @param conversationId owned conversation
     * @param messageId selected last message of the branch
     * @param key idempotency key
     * @return new or previously created job
     */
    @Transactional
    public Job start(Principal principal, String conversationId, String messageId, String key) {
        if (!isId(messageId) || key == null || !KEY.matcher(key).matches()) {
            throw invalid("A branch message and a valid Idempotency-Key are required.");
        }
        String account = principal.user().id();
        String workspace = principal.user().workspaceId();
        accounts.lockActive(account);
        readAccess.require(account, workspace, conversationId);

        // The lock on the parent serializes an export with conversation deletion/mutation.
        if (jdbc.queryForList(ACTIVE_SOURCE + " FOR UPDATE", conversationId, account, workspace)
            .isEmpty()
            || jdbc.queryForList("""
                SELECT id FROM assistant_chat_message WHERE conversation_id = ? AND id = ?
                """, conversationId, messageId).isEmpty()) {
            throw notFound();
        }

        String operation = digest(key.getBytes(StandardCharsets.US_ASCII));
        var existing = jdbc.query("""
            SELECT * FROM assistant_job WHERE account_id = ? AND idempotency_digest = ?
            """, JOB, account, operation).stream().findFirst();
        if (existing.isPresent()) {
            StoredJob job = existing.orElseThrow();
            if (!job.workspaceId().equals(workspace) || !job.conversationId().equals(conversationId)
                || !job.messageId().equals(messageId)) {
                throw conflict("The idempotency key belongs to another job.");
            }
            return job.snapshot();
        }
        Integer active = jdbc.queryForObject(
            """
                SELECT COUNT(*) FROM assistant_job WHERE account_id = ? AND status IN ('queued', 'running')
                """,
            Integer.class, account);
        if (active != null && active >= ACTIVE_LIMIT) {
            throw new AssistantApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED",
                "Wait for an existing background job to finish.");
        }

        String id = UUID.randomUUID().toString();
        String artifact = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        jdbc.update(
            """
                INSERT INTO assistant_job (id, workspace_id, account_id, conversation_id, source_message_id,
                    idempotency_digest, status, progress, artifact_id, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, 'queued', 0, ?, ?, ?)
                """,
            id, workspace, account, conversationId, messageId, operation, artifact, now, now);
        jdbc.update("INSERT INTO assistant_job_outbox (job_id, available_at) VALUES (?, ?)", id,
            now);
        return new StoredJob(id, workspace, account, conversationId, messageId, QUEUED, 0,
            artifact, null, null, now, now).snapshot();
    }

    /**
     * Lists visible jobs newest first.
     *
     * @param principal authenticated account and workspace
     * @param cursor opaque continuation
     * @param limit maximum results
     * @return scoped page
     */
    @Transactional(readOnly = true)
    public Page list(Principal principal, String cursor, int limit) {
        if (limit < 1 || limit > 100) {
            throw invalid("Limit must be between 1 and 100.");
        }
        Cursor after = Cursor.parse(cursor);
        String account = principal.user().id();
        String workspace = principal.user().workspaceId();
        String revision = readAccess.scopeRevision(account, workspace);
        List<StoredJob> rows = jdbc.query("""
            SELECT j.* FROM assistant_job j
            WHERE j.account_id = ? AND j.workspace_id = ?
                AND (j.created_at < ? OR (j.created_at = ? AND j.id < ?))
                AND EXISTS (SELECT 1 FROM assistant_conversation c
                    WHERE c.id = j.conversation_id AND c.account_id = j.account_id
                        AND c.workspace_id = j.workspace_id AND c.read_locked = FALSE)
            """ + VISIBLE_CONVERSATION + " ORDER BY j.created_at DESC, j.id DESC LIMIT ?", JOB,
            account, workspace,
            after.createdAt(), after.createdAt(), after.id(), limit + 1);
        readAccess.verifyScope(account, workspace, revision);
        if (rows.stream().anyMatch(job -> deletions.conversationReadLocked(account, workspace,
            job.conversationId()))) {
            throw conflict("A conversation lock changed. Reload the job list.");
        }
        var items = rows.stream().limit(limit).map(StoredJob::snapshot).toList();
        String next = rows.size() > limit ? Cursor.encode(rows.get(limit - 1)) : null;
        return new Page(items, next);
    }

    /**
     * Returns the current state of an owned job.
     *
     * @param principal authenticated account and workspace
     * @param id job identifier
     * @return current state
     */
    @Transactional(readOnly = true)
    public Job get(Principal principal, String id) {
        return scoped(principal, id, false).snapshot();
    }

    /**
     * Cancels an owned job that has not yet reached a terminal state.
     *
     * @param principal authenticated account and workspace
     * @param id job identifier
     * @return authoritative terminal or cancelled state
     */
    @Transactional
    public Job cancel(Principal principal, String id) {
        StoredJob job = scoped(principal, id, true);
        return job.active() ? finish(job, CANCELLED, job.progress(), null).snapshot()
            : job.snapshot();
    }

    /**
     * Produces the exact UTF-8 artifact prepared by the completed job.
     *
     * @param principal authenticated account and workspace
     * @param id job identifier
     * @return Markdown bytes
     */
    @Transactional(readOnly = true)
    public byte[] artifact(Principal principal, String id) {
        StoredJob job = scoped(principal, id, false);
        var proof = readAccess.require(job.accountId(), job.workspaceId(), job.conversationId());
        if (!COMPLETED.equals(job.status())) {
            throw conflict("The export is not complete.");
        }
        byte[] result = render(job);
        if (!Objects.equals(job.digest(), digest(result))) {
            throw conflict("The recorded export content is no longer available.");
        }
        readAccess.verify(proof);
        return result;
    }

    /**
     * Advances a single due delivery by one durable checkpoint.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void advanceOne() {
        if (deletions.dispatchPaused()) {
            return;
        }
        long now = System.currentTimeMillis();
        List<String> due = jdbc.queryForList("""
            SELECT job_id FROM assistant_job_outbox WHERE available_at <= ?
            ORDER BY available_at, job_id LIMIT 8
            """, String.class, now);
        for (String id : due) {
            // All writers acquire the job before touching its outbox, including erasure.
            var candidate = jdbc.query("""
                SELECT * FROM assistant_job WHERE id = ? FOR UPDATE SKIP LOCKED
                """, JOB, id).stream().findFirst();
            if (candidate.isEmpty()) {
                continue;
            }
            StoredJob job = candidate.orElseThrow();
            long available = jdbc.query("""
                SELECT available_at FROM assistant_job_outbox WHERE job_id = ?
                """, (row, index) -> row.getLong(1), id).stream().findFirst()
                .orElse(Long.MAX_VALUE);
            if (available > now) {
                continue;
            }
            advance(job);
            return;
        }
    }

    private void advance(StoredJob job) {
        if (!job.active()) {
            clearDelivery(job.id());
            return;
        }
        if (deletions.blocksAccount(job.accountId())) {
            return;
        }
        if (jdbc
            .queryForList(ACTIVE_SOURCE, job.conversationId(), job.accountId(), job.workspaceId())
            .isEmpty()) {
            finish(job, CANCELLED, job.progress(), null);
            return;
        }
        if (job.progress() == 50) {
            finish(job, COMPLETED, 100, null);
            return;
        }
        try {
            String fingerprint = digest(render(job));
            long now = System.currentTimeMillis();
            jdbc.update(
                """
                    UPDATE assistant_job SET status = 'running', progress = 50, artifact_digest = ?, updated_at = ?
                    WHERE id = ?
                    """,
                fingerprint, now, job.id());
            jdbc.update("UPDATE assistant_job_outbox SET available_at = ? WHERE job_id = ?",
                now + checkpointDelay, job.id());
        } catch (AssistantApiException error) {
            if (error.status() == HttpStatus.NOT_FOUND) {
                finish(job, CANCELLED, job.progress(), null);
            } else {
                finish(job, FAILED, 0, error.code());
            }
        }
    }

    private StoredJob finish(StoredJob job, String status, int progress, String error) {
        long now = System.currentTimeMillis();
        jdbc.update("""
            UPDATE assistant_job SET status = ?, progress = ?, error_code = ?, updated_at = ?
            WHERE id = ?
            """, status, progress, error, now, job.id());
        clearDelivery(job.id());
        return new StoredJob(job.id(), job.workspaceId(), job.accountId(), job.conversationId(),
            job.messageId(), status, progress, job.artifactId(), job.digest(), error,
            job.createdAt(), now);
    }

    private void clearDelivery(String id) {
        jdbc.update("DELETE FROM assistant_job_outbox WHERE job_id = ?", id);
    }

    private StoredJob scoped(Principal user, String id, boolean lock) {
        StoredJob job = jdbc
            .query(SCOPED_JOB + (lock ? " FOR UPDATE" : ""), JOB, id, user.user().id(),
                user.user().workspaceId())
            .stream().findFirst().orElseThrow(AssistantJobs::notFound);
        readAccess.require(job.accountId(), job.workspaceId(), job.conversationId());
        return job;
    }

    private byte[] render(StoredJob job) {
        List<Source> messages = jdbc.query(
            """
                WITH RECURSIVE ancestry (id, parent_id, depth) AS (
                    SELECT m.id, m.parent_id, 0
                    FROM assistant_chat_message m JOIN assistant_conversation c ON c.id = m.conversation_id
                    JOIN assistant_workspace_member wm
                        ON wm.workspace_id = c.workspace_id AND wm.account_id = c.account_id
                    WHERE m.conversation_id = ? AND m.id = ?
                        AND c.account_id = ? AND c.workspace_id = ? AND c.deleted_at IS NULL
                        AND c.read_locked = FALSE
                    UNION ALL
                    SELECT m.id, m.parent_id, a.depth + 1
                    FROM assistant_chat_message m JOIN ancestry a ON m.id = a.parent_id
                    WHERE m.conversation_id = ? AND a.depth < ?
                )
                SELECT a.parent_id, m.role, LEFT(m.content, ?) AS content
                FROM ancestry a JOIN assistant_chat_message m ON m.id = a.id AND m.conversation_id = ?
                ORDER BY a.depth DESC
                """,
            (row, index) -> new Source(row.getString("parent_id"), row.getString("role"),
                row.getString("content")),
            job.conversationId(), job.messageId(), job.accountId(),
            job.workspaceId(), job.conversationId(), MESSAGE_LIMIT, SOURCE_LENGTH_LIMIT + 1,
            job.conversationId());
        if (messages.isEmpty()) {
            throw notFound();
        }
        if (messages.size() > MESSAGE_LIMIT || messages.get(0).parentId() != null) {
            throw tooLarge();
        }

        var output = new ByteArrayOutputStream();
        output.writeBytes("# 对话导出\n".getBytes(StandardCharsets.UTF_8));
        for (Source message : messages) {
            if (message.content().codePointCount(0,
                message.content().length()) > SOURCE_LENGTH_LIMIT) {
                throw tooLarge();
            }
            byte[] entry = ("\n## " + ("user".equals(message.role()) ? "用户" : "助手")
                + "\n\n" + message.content() + "\n").getBytes(StandardCharsets.UTF_8);
            if (entry.length > ARTIFACT_LIMIT - output.size()) {
                throw tooLarge();
            }
            output.writeBytes(entry);
        }
        return output.toByteArray();
    }

    private static String digest(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable.", e);
        }
    }

    private static boolean isId(String value) {
        if (value == null || value.length() != 36) {
            return false;
        }
        try {
            return UUID.fromString(value).toString().equals(value);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static AssistantApiException notFound() {
        return new AssistantApiException(HttpStatus.NOT_FOUND, "NOT_FOUND",
            "The resource is unavailable.");
    }

    private static AssistantApiException invalid(String message) {
        return new AssistantApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message);
    }

    private static AssistantApiException conflict(String message) {
        return new AssistantApiException(HttpStatus.CONFLICT, "CONFLICT", message);
    }

    private static AssistantApiException tooLarge() {
        return new AssistantApiException(HttpStatus.PAYLOAD_TOO_LARGE, "EXPORT_TOO_LARGE",
            "The selected branch exceeds the background export limit.");
    }

    /**
     * Current job visible to the owner.
     *
     * @param id unique job ID
     * @param kind job type
     * @param conversationId source conversation
     * @param sourceMessageId last message in the selected branch
     * @param status job lifecycle status
     * @param progress durable checkpoint percentage
     * @param artifactId available artifact on completion
     * @param errorCode safe terminal failure code
     * @param createdAt creation timestamp
     * @param updatedAt most recent transition
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Job(String id, String kind, String conversationId, String sourceMessageId,
        String status, int progress, String artifactId, String errorCode, Instant createdAt,
        Instant updatedAt) {
    }

    /**
     * An owner-scoped page of background jobs.
     *
     * @param items visible jobs
     * @param nextCursor continuation or null at the end
     */
    public record Page(List<Job> items,
        @JsonInclude(JsonInclude.Include.ALWAYS) String nextCursor) {
    }

    private record StoredJob(String id, String workspaceId, String accountId, String conversationId,
        String messageId, String status, int progress, String artifactId, String digest,
        String error,
        long createdAt, long updatedAt) {

        private boolean active() {
            return QUEUED.equals(status) || RUNNING.equals(status);
        }

        private Job snapshot() {
            return new Job(id, EXPORT, conversationId, messageId, status, progress,
                COMPLETED.equals(status) ? artifactId : null, error,
                Instant.ofEpochMilli(createdAt),
                Instant.ofEpochMilli(updatedAt));
        }
    }

    private record Source(String parentId, String role, String content) {
    }

    private record Cursor(long createdAt, String id) {

        private static Cursor parse(String token) {
            if (token == null) {
                return new Cursor(Long.MAX_VALUE, "z");
            }
            if (token.length() <= 80) {
                try {
                    String[] parts = new String(Base64.getUrlDecoder().decode(token),
                        StandardCharsets.US_ASCII).split(":", -1);
                    if (parts.length == 2 && isId(parts[1])) {
                        long time = Long.parseLong(parts[0]);
                        if (time > 0) {
                            return new Cursor(time, parts[1]);
                        }
                    }
                } catch (IllegalArgumentException ignored) {
                    // The public validation error below covers all invalid cursor encodings.
                }
            }
            throw invalid("Invalid job cursor.");
        }

        private static String encode(StoredJob last) {
            byte[] contents =
                (last.createdAt() + ":" + last.id()).getBytes(StandardCharsets.US_ASCII);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(contents);
        }
    }
}

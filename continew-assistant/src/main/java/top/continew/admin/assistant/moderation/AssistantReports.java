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

package top.continew.admin.assistant.moderation;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import top.continew.admin.assistant.account.AssistantAccounts.Principal;
import top.continew.admin.assistant.account.AssistantApiException;
import top.continew.admin.assistant.artifact.AssistantHostedSites;
import top.continew.admin.assistant.privacy.AssistantDeletionJournal;
import top.continew.admin.assistant.reminder.AssistantReminderTemplates;
import top.continew.admin.assistant.share.AssistantShares;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Stores consumer reports and workspace-scoped operator decisions with minimal evidence.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
@Service
@ConditionalOnProperty(name = "assistant.enabled", havingValue = "true")
public class AssistantReports {

    private static final String OPEN = "open";
    private static final String ASSISTANT_REPLY = "assistant_reply";
    private static final String SHARED_REPLY = "shared_reply";
    private static final String HOSTED_SITE = "hosted_site";
    private static final String REMINDER_SHARE = "reminder_share";
    private static final Set<String> REASONS = Set.of("safety", "privacy", "spam", "other");
    private static final Set<String> DECISIONS = Set.of("dismiss", "escalate", "withdraw");
    private static final Set<String> REVOCABLE = Set.of(SHARED_REPLY, HOSTED_SITE, REMINDER_SHARE);
    private static final int EVIDENCE_PREVIEW_POINTS = 1024;

    private static final String REPORT_COLUMNS = """
        SELECT id, reporter_account_id, target_type, target_id, conversation_id, reason, status,
            target_created_at, evidence_digest, created_at, target_owner_account_id, reply_index
        FROM assistant_content_report
        """;
    private static final RowMapper<StoredReport> REPORT = (row, index) -> new StoredReport(
        new Receipt(row.getString("id"), row.getString("target_type"), row.getString("target_id"),
            row.getString("reason"), row.getString("status"),
            Instant.ofEpochMilli(row.getLong("created_at"))),
        row.getString("reporter_account_id"), row.getString("conversation_id"),
        Instant.ofEpochMilli(row.getLong("target_created_at")), row.getString("evidence_digest"),
        row.getString("target_owner_account_id"), row.getObject("reply_index", Integer.class));

    private final JdbcTemplate jdbc;
    private final AssistantDeletionJournal deletionJournal;
    private final AssistantShares shares;
    private final AssistantHostedSites sites;
    private final AssistantReminderTemplates reminderTemplates;

    /**
     * Constructs the report service.
     *
     * @param jdbc application data source access
     * @param deletionJournal privacy access gate
     * @param shares authorized immutable shared reply evidence
     * @param sites authorized exact published site evidence
     * @param reminderTemplates authorized exact reminder template evidence
     */
    public AssistantReports(JdbcTemplate jdbc, AssistantDeletionJournal deletionJournal,
        AssistantShares shares, AssistantHostedSites sites,
        AssistantReminderTemplates reminderTemplates) {
        this.jdbc = jdbc;
        this.deletionJournal = deletionJournal;
        this.shares = shares;
        this.sites = sites;
        this.reminderTemplates = reminderTemplates;
    }

    /**
     * Submits a report for an assistant reply currently visible to the reporter.
     *
     * @param reporter authenticated consumer
     * @param request target and reason
     * @return new or previously submitted receipt
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Submission submit(Principal reporter, ReportRequest request) {
        if (request == null || request.targetType() == null
            || request.reason() == null || !REASONS.contains(request.reason())) {
            throw invalid("A valid target type, target ID and report reason are required.");
        }
        String account = reporter.user().id();
        ModerationTarget target = viewerTarget(reporter, request);
        String workspace = target.workspaceId();
        String targetId = target.targetId();

        Optional<StoredReport> existing = jdbc.query(REPORT_COLUMNS + """
            WHERE workspace_id = ? AND reporter_account_id = ? AND target_type = ? AND target_id = ?
            FOR UPDATE
            """, REPORT, workspace, account, request.targetType(), targetId).stream()
            .findFirst();
        if (existing.isPresent()) {
            StoredReport prior = existing.orElseThrow();
            Receipt receipt = prior.receipt();
            if (!receipt.reason().equals(request.reason())
                || !Objects.equals(prior.replyIndex(), request.replyIndex())) {
                throw conflict("The target already has a report from this account.");
            }
            return new Submission(receipt, false);
        }

        long now = System.currentTimeMillis();
        String id = UUID.randomUUID().toString();
        jdbc.update(
            """
                INSERT INTO assistant_content_report
                    (id, workspace_id, reporter_account_id, target_type, target_id, conversation_id, reason,
                     status, target_created_at, evidence_digest, created_at, target_owner_account_id, reply_index)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
            id, workspace, account, request.targetType(), targetId,
            target.conversationId(),
            request.reason(), OPEN, target.createdAtMillis(), digest(target.content()), now,
            ASSISTANT_REPLY.equals(request.targetType()) ? null : target.ownerId(),
            request.replyIndex());
        return new Submission(
            new Receipt(id, request.targetType(), targetId, request.reason(),
                OPEN, Instant.ofEpochMilli(now)),
            true);
    }

    private ModerationTarget viewerTarget(Principal reporter, ReportRequest request) {
        if (SHARED_REPLY.equals(request.targetType())) {
            if (request.targetId() != null || request.revision() != null
                || request.shareToken() == null
                || request.replyIndex() == null || request.replyIndex() < 0
                || request.replyIndex() >= 64) {
                throw invalid("Select an exact shared reply from its current preview.");
            }
            return checkedSource(reporter,
                () -> shares.reportTargetForViewer(reporter, request.shareToken(),
                    request.replyIndex()));
        }
        if (REMINDER_SHARE.equals(request.targetType())) {
            if (request.targetId() != null || request.replyIndex() != null
                || request.shareToken() == null || request.revision() == null) {
                throw invalid("Select the current preview of a shared reminder.");
            }
            return checkedSource(reporter, () -> reminderTemplates.reportTarget(reporter,
                request.shareToken(), request.revision()));
        }
        if (HOSTED_SITE.equals(request.targetType())) {
            if (!validId(request.targetId()) || !validId(request.revision())
                || request.replyIndex() != null || request.shareToken() != null) {
                throw invalid("Select the exact public site release shown in the preview.");
            }
            return checkedSource(reporter, () -> sites.reportTarget(reporter,
                request.targetId(), request.revision()));
        }
        if (!validId(request.targetId()) || request.replyIndex() != null
            || request.shareToken() != null
            || request.revision() != null) {
            throw invalid("A valid report target ID is required.");
        }
        if (!ASSISTANT_REPLY.equals(request.targetType())) {
            throw new AssistantApiException(HttpStatus.FORBIDDEN, "CAPABILITY_UNAVAILABLE",
                "Reports for this target type are unavailable.");
        }
        lockAccounts(reporter.user().id(), reporter.user().id());
        requireCurrentReporter(reporter);
        return ownReply(reporter.user().workspaceId(), reporter.user().id(), request.targetId(),
            true)
            .orElseThrow(() -> missing("Report target not found."));
    }

    private ModerationTarget checkedSource(Principal reporter, Supplier<ModerationTarget> resolve) {
        ModerationTarget initial = resolve.get();
        lockAccounts(reporter.user().id(), initial.ownerId());
        requireCurrentReporter(reporter);
        ModerationTarget selected = resolve.get();
        if (!initial.ownerId().equals(selected.ownerId())
            || !initial.workspaceId().equals(selected.workspaceId())
            || !initial.targetId().equals(selected.targetId())) {
            throw missing("Report target not found.");
        }
        return selected;
    }

    /**
     * Lists oldest unresolved reports in the operator's authorized workspace.
     *
     * @param operator authenticated administration identity
     * @param workspace selected workspace
     * @param limit maximum number of cases to return
     * @return pending cases
     */
    @Transactional(readOnly = true)
    public List<Receipt> pending(Operator operator, String workspace, int limit) {
        if (limit < 1 || limit > 100) {
            throw invalid("A report limit between 1 and 100 is required.");
        }
        requireGrant(operator, workspace, false);
        return jdbc.query(REPORT_COLUMNS + """
            WHERE workspace_id = ? AND status = 'open' ORDER BY created_at, id LIMIT ?
            """, REPORT, workspace, limit).stream().map(StoredReport::receipt).toList();
    }

    /**
     * Reads a case and its audit. Only an active target supplies a bounded preview.
     *
     * @param operator authenticated administration identity
     * @param workspace selected workspace
     * @param id case ID
     * @return case management detail
     */
    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public Detail detail(Operator operator, String workspace, String id) {
        requireGrant(operator, workspace, false);
        StoredReport report = report(workspace, id, false);
        Optional<ModerationTarget> target = reportedTarget(workspace, report, false);
        boolean hidden = report.conversationId() != null && deletionJournal
            .conversationReadLocked(report.sourceOwner(), workspace, report.conversationId());
        if (hidden) {
            target = Optional.empty();
        }
        boolean textPreview = ASSISTANT_REPLY.equals(report.receipt().targetType())
            || SHARED_REPLY.equals(report.receipt().targetType());
        return new Detail(report.receipt(), target.isPresent(),
            textPreview ? target.map(value -> preview(value.content())).orElse(null) : null,
            hidden ? null : report.targetCreatedAt(), hidden ? null : report.evidenceDigest(),
            audit(id, false));
    }

    /**
     * Finalizes a case precisely once with the current target state and operator identity.
     *
     * @param operator authenticated administration identity
     * @param workspace selected workspace
     * @param id case ID
     * @param command requested operator decision
     * @return current case receipt
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Receipt decide(Operator operator, String workspace, String id, Decision command) {
        requireGrant(operator, workspace, true);
        if (command == null || command.decision() == null
            || !DECISIONS.contains(command.decision())) {
            throw invalid("Choose a supported moderation decision.");
        }
        StoredReport current = report(workspace, id, false);
        boolean withdraw = "withdraw".equals(command.decision());
        if (withdraw && !REVOCABLE.contains(current.receipt().targetType())) {
            throw invalid("This kind of report does not support withdrawal.");
        }
        if (current.targetOwnerAccountId() != null) {
            lockAccounts(current.accountId(), current.targetOwnerAccountId());
        }
        Optional<ModerationTarget> target = reportedTarget(workspace, current, true);
        StoredReport locked = report(workspace, id, true);
        Receipt receipt = locked.receipt();
        if (!OPEN.equals(receipt.status())) {
            if (audit(id, true).stream()
                .anyMatch(entry -> command.decision().equals(entry.decision()))) {
                return receipt;
            }
            throw conflict("A decision has already been recorded.");
        }

        String status = "target_unavailable";
        if (target.isPresent()) {
            if (withdraw) {
                status = withdrawTarget(receipt.targetType(), target.orElseThrow());
            } else {
                status = "dismiss".equals(command.decision()) ? "dismissed" : "escalated";
            }
        }
        jdbc.update(
            "UPDATE assistant_content_report SET status = ? WHERE id = ? AND workspace_id = ?",
            status, id, workspace);
        jdbc.update(
            """
                INSERT INTO assistant_report_audit (report_id, operator_id, operator_tenant_id, decision,
                    resulting_status, created_at) VALUES (?, ?, ?, ?, ?, ?)
                """,
            id, operator.userId(), operator.tenantId(), command.decision(), status,
            System.currentTimeMillis());
        return new Receipt(id, receipt.targetType(), receipt.targetId(), receipt.reason(), status,
            receipt.createdAt());
    }

    private String withdrawTarget(String type, ModerationTarget target) {
        try {
            switch (type) {
                case SHARED_REPLY -> shares.withdrawReported(target);
                case HOSTED_SITE -> sites.withdrawReported(target);
                case REMINDER_SHARE -> reminderTemplates.withdrawReported(target);
                default -> throw invalid("This kind of report does not support withdrawal.");
            }
            return "withdrawn";
        } catch (AssistantApiException error) {
            if (HttpStatus.NOT_FOUND.equals(error.status())) {
                return "target_unavailable";
            }
            throw error;
        }
    }

    private void requireGrant(Operator operator, String workspace, boolean decide) {
        if (!validId(workspace)) {
            throw invalid("A valid workspace ID is required.");
        }
        String sql = """
            SELECT can_decide FROM assistant_moderation_grant
            WHERE admin_tenant_id = ? AND admin_user_id = ? AND workspace_id = ?
            """ + (decide ? " FOR UPDATE" : "");
        List<Boolean> grants = jdbc.queryForList(sql, Boolean.class,
            operator.tenantId(), operator.userId(), workspace);
        if (grants.isEmpty() || (decide && !Boolean.TRUE.equals(grants.get(0)))) {
            throw new AssistantApiException(HttpStatus.FORBIDDEN, "FORBIDDEN",
                "Moderation is unavailable for this workspace.");
        }
    }

    private StoredReport report(String workspace, String id, boolean lock) {
        if (!validId(id)) {
            throw missing("Report not found.");
        }
        return jdbc.query(REPORT_COLUMNS + " WHERE workspace_id = ? AND id = ?"
            + (lock ? " FOR UPDATE" : ""),
            REPORT, workspace, id).stream().findFirst()
            .orElseThrow(() -> missing("Report not found."));
    }

    private Optional<ModerationTarget> reportedTarget(String workspace, StoredReport report,
        boolean lock) {
        String id = report.receipt().targetId();
        Optional<ModerationTarget> value;
        try {
            value = switch (report.receipt().targetType()) {
                case ASSISTANT_REPLY -> ownReply(workspace, report.accountId(), id, lock);
                case SHARED_REPLY -> Optional.of(shares.reportTargetForOperator(workspace, id,
                    report.replyIndex()));
                case HOSTED_SITE -> Optional.of(sites.reportedTarget(workspace, id));
                case REMINDER_SHARE -> Optional.of(reminderTemplates.reportedTarget(workspace, id));
                default -> Optional.empty();
            };
        } catch (AssistantApiException error) {
            if (HttpStatus.NOT_FOUND.equals(error.status())) {
                return Optional.empty();
            }
            throw error;
        }
        return value
            .filter(target -> Objects.equals(report.conversationId(), target.conversationId())
                && target.ownerId().equals(report.sourceOwner())
                && workspace.equals(target.workspaceId()) && id.equals(target.targetId())
                && target.createdAtMillis() == report.targetCreatedAt().toEpochMilli()
                && (ASSISTANT_REPLY.equals(report.receipt().targetType())
                    || digest(target.content()).equals(report.evidenceDigest())));
    }

    private Optional<ModerationTarget> ownReply(String workspace, String account, String targetId,
        boolean lock) {
        if (deletionJournal.blocksAccount(account)) {
            return Optional.empty();
        }
        String sql = """
            SELECT c.id, c.read_lock_revision, m.created_at, m.content FROM assistant_chat_message m
            JOIN assistant_conversation c ON c.id = m.conversation_id
            WHERE m.id = ? AND m.role = 'assistant' AND c.account_id = ?
              AND c.workspace_id = ? AND c.deleted_at IS NULL AND c.read_locked = FALSE
            """ + (lock ? " FOR UPDATE" : "");
        return jdbc
            .query(sql, (row, index) -> new Target(row.getString("id"), row.getLong("created_at"),
                row.getString("content"), row.getString("read_lock_revision")), targetId, account,
                workspace)
            .stream().filter(value -> !deletionJournal.blocksConversationRead(account, workspace,
                value.conversationId(), value.readRevision()))
            .findFirst()
            .map(value -> new ModerationTarget(account, workspace, targetId, value.conversationId(),
                value.createdAtMillis(), value.content()));
    }

    private void lockAccounts(String reporter, String owner) {
        jdbc.queryForList("""
            SELECT id FROM assistant_account WHERE id IN (?, ?) ORDER BY id FOR UPDATE
            """, String.class, reporter, owner);
    }

    private void requireCurrentReporter(Principal reporter) {
        String account = reporter.user().id();
        String workspace = reporter.user().workspaceId();
        if (deletionJournal.blocksAccount(account)
            || jdbc.queryForList(
                """
                    SELECT m.membership_revision FROM assistant_session s
                    JOIN assistant_account a ON a.id = s.account_id AND a.deleted_at IS NULL
                    JOIN assistant_workspace_member m ON m.account_id = s.account_id AND m.workspace_id = ?
                    WHERE s.id = ? AND s.account_id = ? AND s.expires_at > ?
                    FOR UPDATE
                    """,
                String.class, workspace, reporter.sessionId(), account, System
                    .currentTimeMillis())
                .stream().noneMatch(revision -> !deletionJournal
                    .blocksWorkspaceMembership(account, workspace, revision))) {
            throw new AssistantApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED",
                "A current reporter session is required.");
        }
    }

    private List<Audit> audit(String id, boolean lock) {
        return jdbc.query("""
            SELECT operator_id, operator_tenant_id, decision, resulting_status, created_at
            FROM assistant_report_audit WHERE report_id = ?
            """ + (lock ? " FOR UPDATE" : ""),
            (row, index) -> new Audit(Long.toString(row.getLong("operator_id")),
                Long.toString(row.getLong("operator_tenant_id")), row.getString("decision"),
                row.getString("resulting_status"), Instant.ofEpochMilli(row.getLong("created_at"))),
            id);
    }

    private static String digest(String content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("Required report digest algorithm is unavailable.",
                failure);
        }
    }

    private static String preview(String content) {
        int count = Math.min(EVIDENCE_PREVIEW_POINTS, content.codePointCount(0, content.length()));
        return content.substring(0, content.offsetByCodePoints(0, count));
    }

    private static boolean validId(String value) {
        if (value == null || value.length() != 36) {
            return false;
        }
        try {
            return UUID.fromString(value).toString().equals(value);
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static AssistantApiException invalid(String message) {
        return new AssistantApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message);
    }

    private static AssistantApiException missing(String message) {
        return new AssistantApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", message);
    }

    private static AssistantApiException conflict(String message) {
        return new AssistantApiException(HttpStatus.CONFLICT, "CONFLICT", message);
    }

    /**
     * Consumer report request.
     *
     * @param targetType product object type
     * @param targetId stable owned object or public address ID when applicable
     * @param reason supported reason code
     * @param shareToken opaque link proof for a shared target
     * @param replyIndex exact zero-based rendered reply index when applicable
     * @param revision exact inspected source revision when applicable
     */
    public record ReportRequest(String targetType, String targetId, String reason,
        String shareToken,
        Integer replyIndex, String revision) {

        /**
         * Creates the stable legacy report request for an owned reply.
         *
         * @param targetType product object type
         * @param targetId stable owned object ID
         * @param reason supported reason code
         */
        public ReportRequest(String targetType, String targetId, String reason) {
            this(targetType, targetId, reason, null, null, null);
        }

        @Override
        public String toString() {
            return "ReportRequest[protected]";
        }
    }

    /**
     * Public receipt containing no copied evidence or operator identities.
     *
     * @param id report ID
     * @param targetType product object type
     * @param targetId stable object ID
     * @param reason report reason code
     * @param status disposition state
     * @param createdAt submission time
     */
    public record Receipt(String id, String targetType, String targetId, String reason,
        String status,
        Instant createdAt) {
    }

    /**
     * Outcome of a report submission.
     *
     * @param receipt public receipt
     * @param created whether a new report was committed
     */
    public record Submission(Receipt receipt, boolean created) {
    }

    /**
     * Authenticated administration identity taken from the access session.
     *
     * @param userId administration user ID
     * @param tenantId administration tenant ID
     */
    public record Operator(long userId, long tenantId) {
    }

    /**
     * Requested moderation decision.
     *
     * @param decision dismiss, escalate, or withdraw for revocable targets
     */
    public record Decision(String decision) {
    }

    /**
     * Evidence and decisions visible to a workspace reviewer.
     *
     * @param report public receipt metadata
     * @param targetAvailable whether the reply is currently visible
     * @param preview bounded text from the still-visible target
     * @param targetCreatedAt original reply creation time
     * @param evidenceDigest original reply fingerprint
     * @param audit durable decision
     */
    public record Detail(Receipt report, boolean targetAvailable, String preview,
        Instant targetCreatedAt,
        String evidenceDigest, List<Audit> audit) {
    }

    /**
     * Durable operator decision.
     *
     * @param operatorId administrative actor ID
     * @param operatorTenantId authenticated administrative tenant
     * @param decision requested decision
     * @param resultingStatus effective final status
     * @param createdAt decision time
     */
    public record Audit(String operatorId, String operatorTenantId, String decision,
        String resultingStatus,
        Instant createdAt) {
    }

    private record Target(String conversationId, long createdAtMillis, String content,
        String readRevision) {
    }

    private record StoredReport(Receipt receipt, String accountId, String conversationId,
        Instant targetCreatedAt,
        String evidenceDigest, String targetOwnerAccountId, Integer replyIndex) {

        private String sourceOwner() {
            return targetOwnerAccountId == null ? accountId : targetOwnerAccountId;
        }
    }
}

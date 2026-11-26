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

package top.continew.admin.assistant.reminder;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import top.continew.admin.assistant.account.AssistantAccounts;
import top.continew.admin.assistant.account.AssistantAccounts.Principal;
import top.continew.admin.assistant.account.AssistantAccounts.User;
import top.continew.admin.assistant.account.AssistantApiException;
import top.continew.admin.assistant.moderation.ModerationTarget;
import top.continew.admin.assistant.privacy.AssistantDeletionJournal;
import top.continew.admin.assistant.privacy.AssistantErasure;
import top.continew.admin.assistant.reminder.AssistantReminders.Create;
import top.continew.admin.assistant.reminder.AssistantReminders.QuietHours;
import top.continew.admin.assistant.reminder.AssistantReminders.Reminder;
import top.continew.admin.assistant.reminder.AssistantReminders.ShareSource;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Shares a reviewed calendar schedule without retaining a second copy of reminder content.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
@Service
@ConditionalOnProperty(name = "assistant.enabled", havingValue = "true")
public class AssistantReminderTemplates {

    private static final int ACTIVE_LIMIT = 8;
    private static final int DAILY_LIMIT = 24;
    private static final Duration LIFETIME = Duration.ofDays(7);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern TOKEN = Pattern.compile("rt_[A-Za-z0-9_-]{43}");
    private static final Pattern REVISION = Pattern.compile("[a-f0-9]{64}");
    private static final RowMapper<Stored> RECORD = (row, index) -> new Stored(
        row.getString("id"), row.getString("account_id"), row.getString("workspace_id"),
        row.getString("reminder_id"), row.getLong("source_epoch"), row.getString("revision_digest"),
        row.getLong("created_at"), row.getLong("expires_at"), row.getLong("revoked_at"));

    private final JdbcTemplate jdbc;
    private final AssistantAccounts accounts;
    private final AssistantReminders reminders;
    private final AssistantDeletionJournal journal;
    private final AssistantErasure erasure;
    private final boolean enabled;
    private final boolean remindersEnabled;

    /**
     * Creates the scoped template service.
     *
     * @param jdbc assistant database
     * @param accounts validated account locks
     * @param reminders authoritative calendar and reminder storage
     * @param journal independent invitation and source revocation
     * @param erasure transactional journal application
     * @param enabled template opt-in
     * @param remindersEnabled reminder scheduling opt-in
     */
    public AssistantReminderTemplates(JdbcTemplate jdbc, AssistantAccounts accounts,
        AssistantReminders reminders, AssistantDeletionJournal journal, AssistantErasure erasure,
        @Value("${assistant.reminder-templates.enabled:false}") boolean enabled,
        @Value("${assistant.reminders.enabled:false}") boolean remindersEnabled) {
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.reminders = reminders;
        this.journal = journal;
        this.erasure = erasure;
        this.enabled = enabled;
        this.remindersEnabled = remindersEnabled;
    }

    /**
     * Gets the exact private schedule the owner may choose to publish.
     *
     * @param principal source owner
     * @param id source reminder ID
     * @return reviewable schedule and revision
     */
    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public Preview preview(Principal principal, String id) {
        requireReady();
        requireSession(principal, false);
        User user = principal.user();
        return render(reminders.shareSource(user.id(), user.workspaceId(), id, false), id);
    }

    /**
     * Issues a short-lived opaque invitation for the reviewed occurrence.
     *
     * @param principal source owner
     * @param id source reminder ID
     * @param revision previously reviewed scope
     * @return one-time token receipt
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Published publish(Principal principal, String id, String revision) {
        requireReady();
        requireRevision(revision);
        User user = principal.user();
        accounts.lockActive(user.id());
        requireSession(principal, true);
        ShareSource source = reminders.shareSource(user.id(), user.workspaceId(), id, true);
        Preview current = render(source, id);
        if (!revision.equals(current.revision())) {
            throw stale();
        }
        Instant now = Instant.ofEpochMilli(System.currentTimeMillis());
        Instant expires = now.plus(LIFETIME).isBefore(source.firstPlannedAt())
            ? now.plus(LIFETIME) : source.firstPlannedAt();
        if (!expires.isAfter(now)) {
            throw stale();
        }
        requireQuota(user.id(), now);
        byte[] random = new byte[32];
        RANDOM.nextBytes(random);
        String token = "rt_" + Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        String shareId = UUID.randomUUID().toString();
        jdbc.update("""
            INSERT INTO assistant_reminder_share (id, account_id, workspace_id, reminder_id,
                source_epoch, token_digest, revision_digest, created_at, expires_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, shareId, user.id(), user.workspaceId(), id, source.epoch(), digest(token),
            revision, now.toEpochMilli(), expires.toEpochMilli());
        return new Published(shareId, token, now, expires);
    }

    /**
     * Lists recent owner-visible invitation metadata.
     *
     * @param principal source owner
     * @param reminderId owned reminder ID
     * @return invitations without tokens
     */
    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public ShareList list(Principal principal, String reminderId) {
        requireReady();
        requireSession(principal, false);
        User user = principal.user();
        var source = reminders.get(user, reminderId);
        String currentRevision = currentRevision(user, source);
        List<Stored> records = jdbc.query("""
            SELECT * FROM assistant_reminder_share
            WHERE account_id = ? AND workspace_id = ? AND reminder_id = ?
            ORDER BY created_at DESC, id DESC LIMIT 32
            """, RECORD, user.id(), user.workspaceId(), reminderId);
        return new ShareList(records.stream().map(row -> summary(row, currentRevision)).toList());
    }

    /**
     * Revokes an owned invitation even after its source was paused.
     *
     * @param principal invitation owner
     * @param shareId invitation identifier
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void revoke(Principal principal, String shareId) {
        requireReady();
        requireId(shareId);
        User user = principal.user();
        accounts.lockActive(user.id());
        requireSession(principal, true);
        Stored row = jdbc.query("""
            SELECT * FROM assistant_reminder_share
            WHERE id = ? AND account_id = ? AND workspace_id = ? FOR UPDATE
            """, RECORD, shareId, user.id(), user.workspaceId()).stream().findFirst()
            .orElseThrow(AssistantReminderTemplates::missing);
        if (row.revokedAt() == 0 && !journal.blocksReminderShare(row.owner(), row.workspace(),
            row.reminderId(), row.id())) {
            erasure.revokeReminderShare(user, shareId);
        }
    }

    /**
     * Displays only snapshot fields to an authenticated holder, without creating anything.
     *
     * @param principal current authenticated recipient
     * @param token opaque invitation
     * @return explicitly reviewable schedule
     */
    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public View read(Principal principal, String token) {
        requireReady();
        requireSession(principal, false);
        String tokenDigest = tokenDigest(token);
        Stored stored = byToken(tokenDigest, false);
        Preview preview = confirmedSource(stored, false);
        requireSession(principal, false);
        byToken(tokenDigest, false);
        return new View(preview.template(), recipientRevision(stored, principal.user()),
            preview.firstPlannedAt(),
            preview.firstDeliveryAt(), Instant.ofEpochMilli(stored.expiresAt()));
    }

    /**
     * Resolves the exact template reviewed by the current recipient for a report.
     *
     * @param principal authenticated recipient and selected workspace
     * @param token opaque invitation from that preview
     * @param revision scoped receipt returned by the template preview
     * @return immutable scoped identity and metadata fingerprint
     */
    public ModerationTarget reportTarget(Principal principal, String token, String revision) {
        requireReady();
        requireRevision(revision);
        requireSession(principal, false);
        String digest = tokenDigest(token);
        Stored row = byToken(digest, false);
        ModerationTarget target = reportEvidence(row, false);
        if (!revision.equals(recipientRevision(row, principal.user()))) {
            throw stale();
        }
        requireSession(principal, false);
        requireReportMember(principal.user().id(), principal.user().workspaceId());
        byToken(digest, false);
        return target;
    }

    /**
     * Resolves active invitation evidence under an operator's established workspace grant.
     *
     * @param workspace authorized originating workspace
     * @param shareId exact persisted invitation identifier
     * @return immutable scoped identity and metadata fingerprint
     */
    public ModerationTarget reportedTarget(String workspace, String shareId) {
        requireReportable();
        return reportEvidence(byShareId(workspace, shareId, false), false);
    }

    /**
     * Retires only the exact still-active invitation under the held owner account lock.
     *
     * @param target already authorized moderation target
     */
    @Transactional(propagation = Propagation.MANDATORY, noRollbackFor = AssistantApiException.class)
    public void withdrawReported(ModerationTarget target) {
        requireReportable();
        var current = reportEvidence(byShareId(target.workspaceId(), target.targetId(), true),
            true);
        if (!target.equals(current)) {
            throw missing();
        }
        erasure.revokeReminderShare(target.ownerId(), target.workspaceId(), target.targetId());
    }

    private ModerationTarget reportEvidence(Stored row, boolean lock) {
        requireReportMember(row.owner(), row.workspace());
        confirmedSource(row, lock);
        if (!available(row)) {
            throw missing();
        }
        return new ModerationTarget(row.owner(), row.workspace(), row.id(), null,
            row.createdAt(), "reminder_share:" + row.revision());
    }

    private void requireReportable() {
        if (!enabled || !remindersEnabled || !journal.acceptsErasure()) {
            throw missing();
        }
    }

    private Stored byShareId(String workspace, String shareId, boolean lock) {
        return jdbc.query("""
            SELECT * FROM assistant_reminder_share WHERE workspace_id = ? AND id = ?
            """ + (lock ? " FOR UPDATE" : ""), RECORD, workspace, shareId).stream()
            .filter(this::available).findFirst().orElseThrow(AssistantReminderTemplates::missing);
    }

    private void requireReportMember(String accountId, String workspaceId) {
        var rows = jdbc.queryForList("""
            SELECT m.membership_revision FROM assistant_workspace_member m
            JOIN assistant_account a ON a.id = m.account_id AND a.deleted_at IS NULL
            WHERE m.account_id = ? AND m.workspace_id = ?
            """, String.class, accountId, workspaceId);
        if (rows.isEmpty() || journal.blocksAccount(accountId)
            || !accounts.allowsMembership(accountId, workspaceId, rows.get(0))) {
            throw missing();
        }
    }

    /**
     * Applies exactly the reviewed start and validates the recipient's own schedule constraints.
     *
     * @param principal authenticated recipient and destination workspace
     * @param token opaque invitation
     * @param revision reviewed recipient revision
     * @param confirm affirmative action from the recipient
     * @return new or prior independent reminder in that recipient scope
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Reminder apply(Principal principal, String token, String revision, boolean confirm) {
        requireReady();
        if (!confirm) {
            throw invalid("Confirm this schedule before applying it.");
        }
        requireRevision(revision);
        requireSession(principal, false);
        String tokenDigest = tokenDigest(token);
        Stored stored = byToken(tokenDigest, false);
        User recipient = principal.user();
        lockAccounts(stored.owner(), recipient.id());
        requireSession(principal, true);
        stored = byToken(tokenDigest, true);
        Preview current = confirmedSource(stored, true);
        if (!revision.equals(recipientRevision(stored, recipient))) {
            throw stale();
        }
        String key = AssistantReminders.identity("assistant-reminder-template/v1\n" + stored.id());
        return reminders.createFromTemplate(recipient, key, current.template().request(),
            current.firstPlannedAt());
    }

    private Preview confirmedSource(Stored row, boolean lock) {
        ShareSource current = reminders.shareSource(row.owner(), row.workspace(), row.reminderId(),
            lock);
        Preview review = render(current, row.reminderId());
        if (row.epoch() != current.epoch() || !row.revision().equals(review.revision())) {
            throw missing();
        }
        return review;
    }

    private static Preview render(ShareSource source, String id) {
        Create data = source.template();
        QuietHours quiet = data.quietHours();
        String revision = digest(String.join("\n", "assistant-reminder-template/v1", id,
            Long.toString(source.epoch()), data.title(), data.startsAtLocal(), data.timeZone(),
            data.cadence(), quiet == null ? "" : quiet.start(), quiet == null ? "" : quiet.end(),
            source.firstPlannedAt().toString(), source.firstDeliveryAt().toString()));
        return new Preview(new Schedule(data.title(), data.startsAtLocal(), data.timeZone(),
            data.cadence(), quiet), revision, source.firstPlannedAt(), source.firstDeliveryAt());
    }

    private static String recipientRevision(Stored share, User recipient) {
        return digest(String.join("\n", "assistant-reminder-apply/v1", share.id(), share.revision(),
            recipient.id(), recipient.workspaceId()));
    }

    private Stored byToken(String digest, boolean lock) {
        return jdbc.query("SELECT * FROM assistant_reminder_share WHERE token_digest = ?"
            + (lock ? " FOR UPDATE" : ""), RECORD, digest).stream()
            .filter(this::available).findFirst().orElseThrow(AssistantReminderTemplates::missing);
    }

    private boolean available(Stored row) {
        return row.revokedAt() == 0 && row.expiresAt() > System.currentTimeMillis()
            && !journal.blocksReminderShare(row.owner(), row.workspace(), row.reminderId(),
                row.id())
            && row.epoch() > journal.reminderPauseCutoff(row.owner(), row.workspace(),
                row.reminderId());
    }

    private String currentRevision(User owner, Reminder reminder) {
        if (!"active".equals(reminder.state())) {
            return null;
        }
        try {
            return render(reminders.shareSource(owner.id(), owner.workspaceId(), reminder.id(),
                false), reminder.id()).revision();
        } catch (AssistantApiException failure) {
            if (failure.status() == HttpStatus.NOT_FOUND) {
                return null;
            }
            throw failure;
        }
    }

    private Summary summary(Stored row, String currentRevision) {
        return new Summary(row.id(), Instant.ofEpochMilli(row.createdAt()),
            Instant.ofEpochMilli(row.expiresAt()),
            row.revokedAt() == 0 ? null : Instant.ofEpochMilli(row.revokedAt()),
            row.revision().equals(currentRevision)
                && available(row));
    }

    private void requireSession(Principal principal, boolean lock) {
        User user = principal.user();
        String sql = """
            SELECT 1 FROM assistant_workspace_member m
            JOIN assistant_account a ON a.id = m.account_id AND a.deleted_at IS NULL
            JOIN assistant_session s ON s.account_id = m.account_id
            WHERE m.account_id = ? AND m.workspace_id = ? AND s.id = ? AND s.expires_at > ?
            """ + (lock ? " FOR UPDATE" : "");
        if (journal.blocksAccount(user.id()) || jdbc.queryForList(sql,
            Integer.class, user.id(), user.workspaceId(), principal.sessionId(),
            System.currentTimeMillis()).isEmpty()) {
            throw new AssistantApiException(HttpStatus.FORBIDDEN, "FORBIDDEN",
                "An active workspace session is required.");
        }
    }

    private void lockAccounts(String owner, String recipient) {
        try {
            if (owner.compareTo(recipient) <= 0) {
                accounts.lockActive(owner);
                if (!owner.equals(recipient)) {
                    accounts.lockActive(recipient);
                }
            } else {
                accounts.lockActive(recipient);
                accounts.lockActive(owner);
            }
        } catch (AssistantApiException failure) {
            if (failure.status() == HttpStatus.UNAUTHORIZED) {
                throw missing();
            }
            throw failure;
        }
    }

    private void requireQuota(String owner, Instant now) {
        Integer active = jdbc.queryForObject("""
            SELECT COUNT(*) FROM assistant_reminder_share
            WHERE account_id = ? AND revoked_at IS NULL AND expires_at > ?
            """, Integer.class, owner, now.toEpochMilli());
        Integer daily = jdbc.queryForObject("""
            SELECT COUNT(*) FROM assistant_reminder_share WHERE account_id = ? AND created_at > ?
            """, Integer.class, owner, now.minus(Duration.ofDays(1)).toEpochMilli());
        if ((active != null && active >= ACTIVE_LIMIT) || (daily != null && daily >= DAILY_LIMIT)) {
            throw new AssistantApiException(HttpStatus.TOO_MANY_REQUESTS, "SHARE_LIMIT",
                "The account has reached its reminder template limit.");
        }
    }

    private void requireReady() {
        if (!enabled || !remindersEnabled || !journal.enabled()) {
            throw new AssistantApiException(HttpStatus.FORBIDDEN, "CAPABILITY_UNAVAILABLE",
                "Reminder template sharing is unavailable.");
        }
        if (!journal.acceptsErasure()) {
            throw new AssistantApiException(HttpStatus.SERVICE_UNAVAILABLE,
                "CAPABILITY_UNAVAILABLE", "Reminder link revocation is temporarily unavailable.");
        }
    }

    private static void requireRevision(String value) {
        if (value == null || !REVISION.matcher(value).matches()) {
            throw invalid("Review this template before continuing.");
        }
    }

    private static void requireId(String value) {
        if (value == null || value.length() != 36) {
            throw missing();
        }
        try {
            if (!UUID.fromString(value).toString().equals(value)) {
                throw missing();
            }
        } catch (IllegalArgumentException ignored) {
            throw missing();
        }
    }

    private static String tokenDigest(String value) {
        if (value == null || !TOKEN.matcher(value).matches()) {
            throw missing();
        }
        return digest(value);
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is required for reminder templates.", failure);
        }
    }

    private static AssistantApiException invalid(String message) {
        return new AssistantApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message);
    }

    private static AssistantApiException missing() {
        return new AssistantApiException(HttpStatus.NOT_FOUND, "NOT_FOUND",
            "The reminder template is unavailable.");
    }

    private static AssistantApiException stale() {
        return new AssistantApiException(HttpStatus.CONFLICT, "SHARE_PREVIEW_STALE",
            "The reminder has changed. Preview the schedule again.");
    }

    /**
     * The only reminder fields that the recipient may copy.
     *
     * @param title reviewed title
     * @param startsAtLocal reviewed first wall-clock time
     * @param timeZone IANA time zone
     * @param cadence once, daily or weekly
     * @param quietHours optional local notification hold interval
     */
    public record Schedule(String title, String startsAtLocal, String timeZone, String cadence,
        @JsonInclude(JsonInclude.Include.ALWAYS) QuietHours quietHours) {

        private Create request() {
            return new Create(title, startsAtLocal, timeZone, cadence, quietHours);
        }

        @Override
        public String toString() {
            return "Schedule[protected]";
        }
    }

    /**
     * An owner's prepublication review.
     *
     * @param template exact fields to be shared
     * @param revision publication revision
     * @param firstPlannedAt scheduled instant
     * @param firstDeliveryAt first delivery after quiet-hour adjustment
     */
    public record Preview(Schedule template, String revision, Instant firstPlannedAt,
        Instant firstDeliveryAt) {
    }

    /**
     * A recipient's scoped review of an active invitation.
     *
     * @param template exact fields to be copied
     * @param revision confirmation revision scoped to the recipient workspace
     * @param firstPlannedAt scheduled instant
     * @param firstDeliveryAt first delivery after quiet-hour adjustment
     * @param expiresAt invitation expiry
     */
    public record View(Schedule template, String revision, Instant firstPlannedAt,
        Instant firstDeliveryAt, Instant expiresAt) {
    }

    /**
     * Publication receipt containing the only clear-text token disclosure.
     *
     * @param id owner-managed share identifier
     * @param token opaque invitation that must be transmitted in a JSON body
     * @param createdAt publication instant
     * @param expiresAt invitation expiry
     */
    public record Published(String id, String token, Instant createdAt, Instant expiresAt) {

        @Override
        public String toString() {
            return "Published[protected]";
        }
    }

    /**
     * Owner metadata for a previously issued invitation.
     *
     * @param id owner-managed share identifier
     * @param createdAt publication instant
     * @param expiresAt invitation expiry
     * @param revokedAt optional explicit revocation instant
     * @param available whether the invitation still has an active policy fence
     */
    public record Summary(String id, Instant createdAt, Instant expiresAt, Instant revokedAt,
        boolean available) {
    }

    /**
     * Bounded owner-visible invitation listing.
     *
     * @param items recent invitation summaries
     */
    public record ShareList(List<Summary> items) {
    }

    private record Stored(String id, String owner, String workspace, String reminderId, long epoch,
        String revision, long createdAt, long expiresAt, long revokedAt) {
    }
}

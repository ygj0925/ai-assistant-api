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
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import top.continew.admin.assistant.account.AssistantAccounts;
import top.continew.admin.assistant.account.AssistantAccounts.User;
import top.continew.admin.assistant.account.AssistantApiException;
import top.continew.admin.assistant.privacy.AssistantDeletionJournal;
import top.continew.admin.assistant.privacy.AssistantErasure;
import top.continew.admin.assistant.reminder.ReminderCalendar.Plan;
import top.continew.admin.assistant.reminder.ReminderCalendar.Slot;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * Stores owner-scoped schedules and atomically publishes in-app reminder occurrences.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
@Service
@ConditionalOnProperty(name = "assistant.enabled", havingValue = "true")
public class AssistantReminders {

    private static final int MAX_REMINDERS = 32;
    private static final int MAX_HISTORY = 90;
    private static final int MAX_TITLE_POINTS = 200;
    private static final int MAX_PAGE = 50;
    private static final String ACTIVE = "active";
    private static final String PAUSED = "paused";
    private static final String EXPIRED = "expired";
    private static final String SELECT = """
        SELECT id, account_id, workspace_id, title, starts_at_local, time_zone, cadence,
            quiet_start, quiet_end, status, config_epoch, next_slot_day, next_wake_at,
            request_digest, created_at, updated_at
        FROM assistant_reminder
        """;
    private static final String OWNED = " WHERE account_id = ? AND workspace_id = ?";
    private static final String OCCURRENCES = """
        SELECT o.id, o.reminder_id, r.account_id, r.workspace_id, r.title, o.scheduled_local_at,
            o.planned_at, o.delivery_at, o.occurred_at, o.outcome, o.read_at
        FROM assistant_reminder_occurrence o JOIN assistant_reminder r ON r.id = o.reminder_id
        WHERE r.account_id = ? AND r.workspace_id = ?
        """;
    private static final RowMapper<Stored> ROW = (row, index) -> new Stored(
        row.getString("id"), new Scope(row.getString("account_id"), row.getString("workspace_id")),
        row.getString("title"), ReminderCalendar.plan(row.getString("starts_at_local"),
            row.getString("time_zone"), row.getString("cadence"), row.getString("quiet_start"),
            row.getString("quiet_end")),
        row.getString("status"), row.getLong("config_epoch"),
        optionalLong(row, "next_slot_day"), optionalLong(row, "next_wake_at"),
        row.getString("request_digest"), row.getLong("created_at"), row.getLong("updated_at"));
    private static final RowMapper<Occurrence> OCCURRENCE = (row, index) -> {
        Long readAt = optionalLong(row, "read_at");
        return new Occurrence(row.getString("id"), row.getString("reminder_id"),
            row.getString("title"), row.getString("scheduled_local_at"),
            Instant.ofEpochMilli(row.getLong("planned_at")),
            Instant.ofEpochMilli(row.getLong("delivery_at")),
            Instant.ofEpochMilli(row.getLong("occurred_at")), row.getString("outcome"),
            readAt == null ? null : Instant.ofEpochMilli(readAt));
    };

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final AssistantAccounts accounts;
    private final AssistantDeletionJournal journal;
    private final AssistantErasure erasure;
    private final boolean enabled;
    private final Clock clock = Clock.systemUTC();

    /**
     * Connects local reminder scheduling to the assistant account and deletion boundaries.
     *
     * @param jdbc assistant database
     * @param transactionManager manager for independent worker transactions
     * @param accounts account validity and locking
     * @param journal independent pause and cancellation controls
     * @param erasure application of durable reminder operations
     * @param enabled reminder policy switch
     */
    public AssistantReminders(JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
        AssistantAccounts accounts, AssistantDeletionJournal journal, AssistantErasure erasure,
        @Value("${assistant.reminders.enabled:false}") boolean enabled) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setTimeout(8);
        this.accounts = accounts;
        this.journal = journal;
        this.erasure = erasure;
        this.enabled = enabled;
    }

    /**
     * Reports whether durable in-app scheduling is available.
     *
     * @return whether reminders can be used
     */
    public boolean available() {
        return enabled && journal.acceptsErasure();
    }

    /**
     * Creates a calendar schedule or returns the original request's reminder.
     *
     * @param owner current account and workspace
     * @param key UUID identifying this request
     * @param input reminder title and local schedule
     * @return current reminder
     */
    @Transactional
    public Reminder create(User owner, String key, Create input) {
        return createValidated(owner, key, input, null);
    }

    Reminder createFromTemplate(User owner, String key, Create input, Instant reviewedStart) {
        return createValidated(owner, key, input, reviewedStart);
    }

    private Reminder createValidated(User owner, String key, Create input, Instant reviewedStart) {
        requireReady();
        requireId(key);
        String title = title(input.title());
        QuietHours quiet = input.quietHours();
        Plan plan;
        try {
            plan = ReminderCalendar.plan(input.startsAtLocal(), input.timeZone(), input.cadence(),
                quiet == null ? null : quiet.start(), quiet == null ? null : quiet.end());
        } catch (DateTimeException | IllegalArgumentException failure) {
            throw invalid("Choose a valid calendar time, IANA time zone, cadence and quiet hours.");
        }
        if (quiet != null && plan.quiet() == null) {
            throw invalid("Choose both quiet-hour boundaries.");
        }
        Scope scope = scope(owner, true);
        String id = identity("assistant-reminder/v1\n" + scope.accountId() + "\n"
            + scope.workspaceId() + "\n" + key);
        if (blocked(scope, id)) {
            throw notFound();
        }
        String fingerprint = HexFormat.of().formatHex(sha256(title + "\n" + input.startsAtLocal()
            + "\n" + input.timeZone() + "\n" + input.cadence() + "\n"
            + (quiet == null ? "" : quiet.start() + "\n" + quiet.end())));
        var previous = jdbc.query(SELECT + OWNED + " AND id = ?", ROW, scope.accountId(),
            scope.workspaceId(), id).stream().findFirst();
        if (previous.isPresent()) {
            Stored row = previous.orElseThrow();
            if (!row.requestDigest().equals(fingerprint)) {
                throw conflict("This request key already belongs to a different reminder.");
            }
            return exposed(row);
        }
        if (cutoff(scope, id) > 0) {
            throw notFound();
        }
        Integer owned =
            jdbc.queryForObject("SELECT COUNT(*) FROM assistant_reminder WHERE account_id = ?",
                Integer.class, scope.accountId());
        if (owned != null && owned >= MAX_REMINDERS) {
            throw conflict("The account has reached its reminder limit.");
        }
        Instant now = clock.instant();
        Slot next = ReminderCalendar.firstFuture(plan, now);
        if (reviewedStart != null && (next == null || !reviewedStart.equals(next.plannedAt()))) {
            throw new AssistantApiException(HttpStatus.CONFLICT, "SHARE_PREVIEW_STALE",
                "The reviewed schedule passed. Preview a new template.");
        }
        if (next == null) {
            throw invalid("The one-time reminder needs a future date.");
        }
        jdbc.update("""
            INSERT INTO assistant_reminder
                (id, account_id, workspace_id, title, starts_at_local, time_zone, cadence,
                 quiet_start, quiet_end, status, request_digest, config_epoch,
                 next_slot_day, next_wake_at, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'active', ?, 1, ?, ?, ?, ?)
            """, id, scope.accountId(), scope.workspaceId(), title, input.startsAtLocal(),
            input.timeZone(), input.cadence(), quiet == null ? null : quiet.start(),
            quiet == null ? null : quiet.end(), fingerprint, next.day(),
            next.deliveryAt().toEpochMilli(),
            now.toEpochMilli(), now.toEpochMilli());
        return exposed(owned(scope, id));
    }

    /**
     * Lists current records in one workspace.
     *
     * @param owner selected owner
     * @param cursor optional continuation
     * @param limit optional page size
     * @return owner reminder page
     */
    public Page<Reminder> list(User owner, String cursor, Integer limit) {
        requireReady();
        Scope scope = scope(owner, false);
        int size = pageSize(limit);
        Position after = Position.read(cursor, scope, "reminders");
        var arguments = new ArrayList<Object>(List.of(scope.accountId(), scope.workspaceId()));
        String where = boundary("created_at", "id", after, arguments);
        arguments.add(size + 1);
        List<Stored> rows = jdbc.query(SELECT + OWNED + where
            + " ORDER BY created_at DESC, id DESC LIMIT ?", ROW, arguments.toArray());
        List<Stored> visible = rows.subList(0, Math.min(rows.size(), size));
        String next = null;
        if (rows.size() > size) {
            Stored last = visible.get(visible.size() - 1);
            next = new Position(last.createdAt(), last.id()).encode(scope, "reminders");
        }
        return new Page<>(visible.stream().filter(row -> !blocked(scope, row.id()))
            .map(this::exposed).toList(), next);
    }

    /**
     * Reads a single owned schedule.
     *
     * @param owner selected owner
     * @param id reminder identifier
     * @return current reminder
     */
    public Reminder get(User owner, String id) {
        requireReady();
        return exposed(owned(scope(owner, false), id));
    }

    ShareSource shareSource(String accountId, String workspaceId, String id, boolean lock) {
        requireReady();
        Scope scope = new Scope(accountId, workspaceId);
        if (!member(scope, lock)) {
            throw notFound();
        }
        Stored row = owned(scope, id, lock);
        if (!ACTIVE.equals(effectiveStatus(row))) {
            throw notFound();
        }
        Slot next = ReminderCalendar.firstFuture(row.plan(), clock.instant());
        if (next == null) {
            throw notFound();
        }
        var quiet = row.plan().quiet();
        Create template = new Create(row.title(), ReminderCalendar.format(next.local()),
            row.plan().zone().getId(), row.plan().cadence().value, quiet == null ? null
                : new QuietHours(quiet.start().toString(), quiet.end().toString()));
        return new ShareSource(template, row.epoch(), next.plannedAt(), next.deliveryAt());
    }

    /**
     * Pauses or explicitly resumes the reminder from a future slot.
     *
     * @param owner selected owner
     * @param id reminder identifier
     * @param active desired dispatch state
     * @return current reminder
     */
    @Transactional
    public Reminder setActive(User owner, String id, boolean active) {
        requireReady();
        Scope scope = scope(owner, true);
        Stored row = owned(scope, id);
        String current = effectiveStatus(row);
        if (active && ACTIVE.equals(current) || !active && PAUSED.equals(current)) {
            return exposed(row);
        }
        if (!ACTIVE.equals(current) && !PAUSED.equals(current)) {
            throw conflict("This reminder has already reached its final status.");
        }
        if (!active) {
            erasure.pauseReminder(owner, id);
        } else {
            Instant now = clock.instant();
            Slot next = ReminderCalendar.firstFuture(row.plan(), now);
            long epoch = Math.incrementExact(Math.max(now.toEpochMilli(),
                Math.max(row.epoch(), cutoff(scope, id))));
            advance(row.id(), next == null ? EXPIRED : ACTIVE, epoch, next,
                next == null ? null : next.deliveryAt(), now);
        }
        return exposed(owned(scope, id));
    }

    /**
     * Cancels and erases an owned reminder and its occurrences.
     *
     * @param owner selected owner
     * @param id reminder identifier
     */
    @Transactional
    public void delete(User owner, String id) {
        requireReady();
        Scope scope = scope(owner, true);
        owned(scope, id);
        erasure.deleteReminder(owner, id);
    }

    /**
     * Lists durable in-app notices and terminal run records.
     *
     * @param owner selected owner
     * @param reminderId optional reminder filter
     * @param cursor optional continuation
     * @param limit optional page size
     * @return owner occurrence page
     */
    public Page<Occurrence> occurrences(User owner, String reminderId, String cursor,
        Integer limit) {
        requireReady();
        Scope scope = scope(owner, false);
        var arguments = new ArrayList<Object>(List.of(scope.accountId(), scope.workspaceId()));
        String query = OCCURRENCES;
        if (reminderId != null) {
            owned(scope, reminderId);
            query += " AND o.reminder_id = ?";
            arguments.add(reminderId);
        }
        String section = reminderId == null ? "occurrences" : reminderId;
        Position after = Position.read(cursor, scope, section);
        int size = pageSize(limit);
        query += boundary("o.occurred_at", "o.id", after, arguments);
        arguments.add(size + 1);
        List<Occurrence> rows =
            jdbc.query(query + " ORDER BY o.occurred_at DESC, o.id DESC LIMIT ?",
                OCCURRENCE, arguments.toArray());
        List<Occurrence> visible = rows.subList(0, Math.min(rows.size(), size));
        String next = null;
        if (rows.size() > size) {
            Occurrence last = visible.get(visible.size() - 1);
            next = new Position(last.occurredAt().toEpochMilli(), last.id()).encode(scope, section);
        }
        return new Page<>(visible.stream().filter(item -> !blocked(scope, item.reminderId()))
            .toList(), next);
    }

    /**
     * Marks an owned occurrence as read without changing its original read time on retries.
     *
     * @param owner current owner
     * @param id occurrence identifier
     * @return current occurrence
     */
    @Transactional
    public Occurrence markRead(User owner, String id) {
        requireReady();
        requireId(id);
        Scope scope = scope(owner, true);
        Occurrence row = occurrence(scope, id);
        if (row.readAt() == null) {
            long readAt = Math.max(clock.millis(), row.occurredAt().toEpochMilli());
            jdbc.update(
                "UPDATE assistant_reminder_occurrence SET read_at = ? WHERE id = ? AND read_at IS NULL",
                readAt, id);
        }
        return occurrence(scope, id);
    }

    /**
     * Publishes due in-app occurrences in bounded independent transactions.
     */
    @Scheduled(fixedDelay = 750)
    public void tick() {
        advanceDue(clock.instant());
    }

    void advanceDue(Instant now) {
        if (!available() || journal.dispatchPaused()) {
            return;
        }
        long due = now.toEpochMilli();
        for (Stored row : jdbc.query(
            SELECT
                + """
                    WHERE status = 'active' AND next_wake_at <= ? AND EXISTS (
                        SELECT 1 FROM assistant_workspace_member wm WHERE wm.account_id = assistant_reminder.account_id
                            AND wm.workspace_id = assistant_reminder.workspace_id
                    )
                    ORDER BY next_wake_at, id LIMIT 8
                    """,
            ROW, due)) {
            transactions.executeWithoutResult(ignored -> publish(row, now));
        }
    }

    private void publish(Stored candidate, Instant now) {
        Scope scope = candidate.scope();
        if (blocked(scope, candidate.id()) || jdbc.queryForList(
            """
                SELECT id FROM assistant_account WHERE id = ? AND deleted_at IS NULL FOR UPDATE SKIP LOCKED
                """,
            String.class, scope.accountId()).isEmpty() || !member(scope, true)) {
            return;
        }
        var rows = jdbc.query(SELECT + OWNED
            + " AND id = ? AND status = 'active' AND next_wake_at <= ? FOR UPDATE", ROW,
            scope.accountId(), scope.workspaceId(), candidate.id(), now.toEpochMilli());
        if (rows.isEmpty()) {
            return;
        }
        Stored current = rows.get(0);
        if (blocked(scope, current.id()) || !ACTIVE.equals(effectiveStatus(current))) {
            return;
        }
        var decision = ReminderCalendar.due(current.plan(), current.nextDay(), now);
        if (decision.occurrence() != null) {
            record(current, decision.occurrence(), decision.expired(), now);
        }
        String status =
            decision.next() != null ? ACTIVE : decision.expired() ? EXPIRED : "completed";
        advance(current.id(), status, current.epoch(), decision.next(), decision.wakeAt(), now);
    }

    private void record(Stored reminder, Slot occurrence, boolean expired, Instant now) {
        String id = identity("assistant-reminder-occurrence/v1\n" + reminder.id() + "\n"
            + reminder.epoch() + "\n" + occurrence.day());
        if (!jdbc.queryForList("SELECT id FROM assistant_reminder_occurrence WHERE id = ?",
            String.class, id).isEmpty()) {
            return;
        }
        jdbc.update("""
            INSERT INTO assistant_reminder_occurrence
                (id, reminder_id, config_epoch, slot_day, scheduled_local_at, planned_at,
                 delivery_at, occurred_at, outcome)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, id, reminder.id(), reminder.epoch(), occurrence.day(),
            ReminderCalendar.format(occurrence.local()), occurrence.plannedAt().toEpochMilli(),
            occurrence.deliveryAt().toEpochMilli(), now.toEpochMilli(),
            expired ? EXPIRED : "delivered");
        var history = jdbc.queryForList("""
            SELECT id FROM assistant_reminder_occurrence WHERE reminder_id = ?
            ORDER BY occurred_at DESC, id DESC LIMIT ?
            """, String.class, reminder.id(), MAX_HISTORY + 1);
        if (history.size() > MAX_HISTORY) {
            jdbc.update("DELETE FROM assistant_reminder_occurrence WHERE id = ?",
                history.get(MAX_HISTORY));
        }
    }

    private void advance(String id, String status, long epoch, Slot next, Instant wakeAt,
        Instant now) {
        jdbc.update(
            """
                UPDATE assistant_reminder SET status = ?, config_epoch = ?, next_slot_day = ?, next_wake_at = ?, updated_at = ?
                WHERE id = ?
                """,
            status, epoch, next == null ? null : next.day(),
            wakeAt == null ? null : wakeAt.toEpochMilli(), now.toEpochMilli(), id);
    }

    private Occurrence occurrence(Scope scope, String id) {
        return jdbc.query(OCCURRENCES + " AND o.id = ?", OCCURRENCE, scope.accountId(),
            scope.workspaceId(), id).stream()
            .filter(occurrence -> !blocked(scope, occurrence.reminderId()))
            .findFirst().orElseThrow(AssistantReminders::notFound);
    }

    private Stored owned(Scope scope, String id) {
        return owned(scope, id, false);
    }

    private Stored owned(Scope scope, String id, boolean lock) {
        requireId(id);
        if (blocked(scope, id)) {
            throw notFound();
        }
        return jdbc
            .query(SELECT + OWNED + " AND id = ?" + (lock ? " FOR UPDATE" : ""), ROW,
                scope.accountId(),
                scope.workspaceId(), id)
            .stream().filter(row -> !blocked(scope, row.id()))
            .findFirst().orElseThrow(AssistantReminders::notFound);
    }

    private Reminder exposed(Stored row) {
        if (blocked(row.scope(), row.id())) {
            throw notFound();
        }
        String current = effectiveStatus(row);
        Slot next = ACTIVE.equals(current) && row.nextDay() != null
            ? ReminderCalendar.slot(row.plan(), row.nextDay()) : null;
        var quiet = row.plan().quiet();
        return new Reminder(row.id(), row.title(), ReminderCalendar.format(row.plan().start()),
            row.plan().zone().getId(), row.plan().cadence().value,
            quiet == null ? null : new QuietHours(quiet.start().toString(), quiet.end().toString()),
            current, next == null ? null : next.plannedAt(),
            next == null ? null : Instant.ofEpochMilli(row.nextWake()),
            Instant.ofEpochMilli(row.createdAt()), Instant.ofEpochMilli(row.updatedAt()));
    }

    private String effectiveStatus(Stored row) {
        return ACTIVE.equals(row.status()) && row.epoch() <= cutoff(row.scope(), row.id()) ? PAUSED
            : row.status();
    }

    private Scope scope(User owner, boolean lock) {
        Scope scope = new Scope(owner.id(), owner.workspaceId());
        if (lock) {
            accounts.lockActive(scope.accountId());
        }
        if (!member(scope, lock)) {
            throw new AssistantApiException(HttpStatus.FORBIDDEN, "FORBIDDEN",
                "Workspace access is required.");
        }
        return scope;
    }

    private boolean member(Scope scope, boolean lock) {
        String sql =
            "SELECT account_id FROM assistant_workspace_member WHERE account_id = ? AND workspace_id = ?";
        return !jdbc
            .queryForList(sql + (lock ? " FOR UPDATE" : ""), String.class, scope.accountId(),
                scope.workspaceId())
            .isEmpty();
    }

    private boolean blocked(Scope scope, String id) {
        return journal.blocksReminder(scope.accountId(), scope.workspaceId(), id);
    }

    private long cutoff(Scope scope, String id) {
        return journal.reminderPauseCutoff(scope.accountId(), scope.workspaceId(), id);
    }

    private void requireReady() {
        if (!enabled || !journal.enabled()) {
            throw new AssistantApiException(HttpStatus.FORBIDDEN, "CAPABILITY_UNAVAILABLE",
                "Scheduled reminders are unavailable.");
        }
        if (!journal.acceptsErasure()) {
            throw new AssistantApiException(HttpStatus.SERVICE_UNAVAILABLE,
                "CAPABILITY_UNAVAILABLE",
                "Scheduled reminder revocation is temporarily unavailable.");
        }
    }

    private static String title(String title) {
        if (title == null || title.length() > MAX_TITLE_POINTS * 2 || title.isBlank()
            || title.codePointCount(0, title.length()) > MAX_TITLE_POINTS
            || !StandardCharsets.UTF_8.newEncoder().canEncode(title)
            || title.codePoints().anyMatch(Character::isISOControl)) {
            throw invalid("Enter a plain reminder title of at most 200 characters.");
        }
        return title.strip();
    }

    private static int pageSize(Integer requested) {
        int value = requested == null ? 20 : requested;
        if (value < 1 || value > MAX_PAGE) {
            throw invalid("Choose a reminder page size from 1 to 50.");
        }
        return value;
    }

    private static String boundary(String clockColumn, String idColumn, Position after,
        List<Object> arguments) {
        if (after == null) {
            return "";
        }
        arguments.add(after.millis());
        arguments.add(after.millis());
        arguments.add(after.id());
        return " AND (" + clockColumn + " < ? OR (" + clockColumn + " = ? AND " + idColumn
            + " < ?))";
    }

    private static Long optionalLong(ResultSet row, String name) throws SQLException {
        long result = row.getLong(name);
        return row.wasNull() ? null : result;
    }

    static String identity(String scope) {
        byte[] bits = sha256(scope);
        bits[6] = (byte) ((bits[6] & 0x0f) | 0x80);
        bits[8] = (byte) ((bits[8] & 0x3f) | 0x80);
        var buffer = ByteBuffer.wrap(bits);
        return new UUID(buffer.getLong(), buffer.getLong()).toString();
    }

    private static byte[] sha256(String scope) {
        try {
            return MessageDigest.getInstance("SHA-256")
                .digest(scope.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("Reminder identity unavailable.", error);
        }
    }

    private static void requireId(String id) {
        try {
            if (id != null && id.length() == 36 && UUID.fromString(id).toString().equals(id)) {
                return;
            }
        } catch (IllegalArgumentException ignored) {
            // Normalize the public validation response.
        }
        throw invalid("A valid reminder identifier is required.");
    }

    private static AssistantApiException invalid(String message) {
        return new AssistantApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message);
    }

    private static AssistantApiException notFound() {
        return new AssistantApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Reminder not found.");
    }

    private static AssistantApiException conflict(String message) {
        return new AssistantApiException(HttpStatus.CONFLICT, "CONFLICT", message);
    }

    /**
     * Owner-entered wall-clock boundaries in HH:mm format.
     *
     * @param start inclusive local boundary
     * @param end exclusive local boundary
     */
    public record QuietHours(String start, String end) {
    }

    /**
     * Owner-entered schedule.
     *
     * @param title reminder label
     * @param startsAtLocal first intended wall time
     * @param timeZone IANA zone identifier
     * @param cadence once, daily, or weekly
     * @param quietHours optional local hold interval
     */
    public record Create(String title, String startsAtLocal, String timeZone, String cadence,
        QuietHours quietHours) {

        @Override
        public String toString() {
            return "Create[protected]";
        }
    }

    /**
     * Current scheduling state and next instants.
     *
     * @param id reminder identifier
     * @param title owner-supplied label
     * @param startsAtLocal first intended wall time
     * @param timeZone IANA time zone
     * @param cadence once, daily, or weekly
     * @param quietHours optional local hold interval
     * @param state scheduling state
     * @param nextScheduledAt next intended instant if active
     * @param nextDeliveryAt earliest next notification if active
     * @param createdAt original creation instant
     * @param updatedAt last scheduling change
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Reminder(String id, String title, String startsAtLocal, String timeZone,
        String cadence, QuietHours quietHours, String state, Instant nextScheduledAt,
        Instant nextDeliveryAt, Instant createdAt, Instant updatedAt) {

        @Override
        public String toString() {
            return "Reminder[protected]";
        }
    }

    /**
     * One persisted notification or final expired run.
     *
     * @param id occurrence identifier
     * @param reminderId originating reminder
     * @param title owner-supplied label
     * @param scheduledLocalAt logical wall slot
     * @param plannedAt converted intent time
     * @param deliveryAt first time allowed by quiet hours
     * @param occurredAt actual persisted resolution time
     * @param outcome delivered or expired
     * @param readAt first acknowledgement time if read
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Occurrence(String id, String reminderId, String title, String scheduledLocalAt,
        Instant plannedAt, Instant deliveryAt, Instant occurredAt, String outcome, Instant readAt) {

        @Override
        public String toString() {
            return "Occurrence[protected]";
        }
    }

    /**
     * Scoped continuation page.
     *
     * @param items current visible rows
     * @param nextCursor continuation or explicit null
     * @param <T> portable DTO type
     */
    public record Page<T>(List<T> items,
        @JsonInclude(JsonInclude.Include.ALWAYS) String nextCursor) {
    }

    record ShareSource(Create template, long epoch, Instant firstPlannedAt,
        Instant firstDeliveryAt) {
    }

    private record Scope(String accountId, String workspaceId) {
    }

    private record Stored(String id, Scope scope, String title, Plan plan, String status,
        long epoch,
        Long nextDay, Long nextWake, String requestDigest, long createdAt, long updatedAt) {
    }

    private record Position(long millis, String id) {

        private static Position read(String value, Scope scope, String purpose) {
            if (value == null) {
                return null;
            }
            if (!value.isEmpty() && value.length() <= 230) {
                try {
                    String raw =
                        new String(Base64.getUrlDecoder().decode(value), StandardCharsets.US_ASCII);
                    String[] parts = raw.split(":", -1);
                    if (parts.length == 5 && scope.accountId().equals(parts[0])
                        && scope.workspaceId().equals(parts[1]) && purpose.equals(parts[2])) {
                        requireId(parts[4]);
                        return new Position(Long.parseLong(parts[3]), parts[4]);
                    }
                } catch (IllegalArgumentException ignored) {
                    // Normalize the public validation response.
                }
            }
            throw invalid("The reminder cursor is invalid for this account and workspace.");
        }

        private String encode(Scope scope, String purpose) {
            String raw =
                scope.accountId() + ":" + scope.workspaceId() + ":" + purpose + ":" + millis
                    + ":" + id;
            return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.US_ASCII));
        }
    }
}

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
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import top.continew.admin.assistant.account.AssistantAccounts.Principal;
import top.continew.admin.assistant.account.AssistantAccounts.User;
import top.continew.admin.assistant.account.AssistantApiException;
import top.continew.admin.assistant.connector.AssistantSandboxConnector;
import top.continew.admin.assistant.privacy.AssistantDeletionJournal;
import top.continew.admin.assistant.privacy.AssistantErasure;
import top.continew.admin.assistant.reminder.AssistantReminders.QuietHours;

import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Stores authorized in-app reminders for verified events from the isolated simulator.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
@Service
@ConditionalOnProperty(name = "assistant.enabled", havingValue = "true")
public class AssistantEventReminders {

    static final String KIND = "fixture_notice_recorded";
    private static final String ACTIVE = "active";
    private static final String PAUSED = "paused";
    private static final String REVOKED = "authorization_revoked";
    private static final String WAITING = "waiting";
    private static final String DELIVERED = "delivered";
    private static final String SKIPPED = "skipped";
    private static final String WITHDRAWN = "withdrawn";
    private static final String EXPIRED = "expired";
    private static final int MAX_SUBSCRIPTIONS = 8;
    private static final String SUBSCRIPTIONS = """
        SELECT e.id, e.title, e.authorization_id, e.time_zone, e.quiet_start, e.quiet_end,
            e.active, e.config_epoch, e.created_at, e.updated_at, g.revoked_at
        FROM assistant_sandbox_event_reminder e
        JOIN assistant_sandbox_connector_grant g ON g.id = e.authorization_id
            AND g.account_id = e.account_id AND g.workspace_id = e.workspace_id
        WHERE e.account_id = ? AND e.workspace_id = ? AND g.membership_revision = ?
        """;
    private static final String OCCURRENCES =
        """
            SELECT id, operation_id, state, subscription_epoch, received_at, delivery_at, delivered_at, read_at
            FROM assistant_sandbox_event_occurrence WHERE reminder_id = ?
            """;
    private static final RowMapper<Stored> SUBSCRIPTION_ROW = (row, index) -> new Stored(
        row.getString("id"), row.getString("title"), row.getString("authorization_id"),
        row.getString("time_zone"), row.getString("quiet_start"), row.getString("quiet_end"),
        row.getBoolean("active"), row.getLong("config_epoch"), row.getLong("created_at"),
        row.getLong("updated_at"), row.getObject("revoked_at", Long.class) != null);
    private static final RowMapper<OccurrenceRow> OCCURRENCE_ROW =
        (row, index) -> new OccurrenceRow(
            row.getString("id"), row.getString("operation_id"), row.getString("state"),
            row.getLong("subscription_epoch"), row.getLong("received_at"),
            row.getLong("delivery_at"),
            row.getObject("delivered_at", Long.class), row.getObject("read_at", Long.class));

    private final JdbcTemplate jdbc;
    private final AssistantSandboxConnector connector;
    private final AssistantDeletionJournal journal;
    private final AssistantErasure erasure;
    private final TransactionTemplate transactions;
    private final boolean enabled;

    /**
     * Wires event receipts, durable revocation and bounded transaction scheduling.
     *
     * @param jdbc assistant database
     * @param connector local trusted receipt source
     * @param journal independent revocations and pause cutoffs
     * @param erasure privacy lifecycle
     * @param manager transaction manager
     * @param enabled event reminder feature flag
     */
    public AssistantEventReminders(JdbcTemplate jdbc, AssistantSandboxConnector connector,
        AssistantDeletionJournal journal, AssistantErasure erasure,
        PlatformTransactionManager manager,
        @Value("${assistant.sandbox-event-reminders.enabled:false}") boolean enabled) {
        this.jdbc = jdbc;
        this.connector = connector;
        this.journal = journal;
        this.erasure = erasure;
        this.enabled = enabled;
        transactions = new TransactionTemplate(manager);
        transactions.setIsolationLevel(TransactionTemplate.ISOLATION_READ_COMMITTED);
    }

    /**
     * Reports whether this consumer workspace can receive isolated simulator events.
     *
     * @param user selected workspace member
     * @return event reminder capability availability
     */
    public boolean available(User user) {
        return enabled && connector.eventSourceAvailable(user);
    }

    SubscriptionList list(Principal actor) {
        User user = actor.user();
        String membership = connector.eventMembership(actor, false);
        var items = jdbc.query(SUBSCRIPTIONS + "ORDER BY e.created_at DESC, e.id DESC LIMIT 8",
            SUBSCRIPTION_ROW, user.id(), user.workspaceId(), membership).stream()
            .filter(row -> !deleted(user, row.id())).map(row -> expose(user, row)).toList();
        verifyRead(actor, membership);
        return new SubscriptionList(items);
    }

    /**
     * Creates an active subscription for an existing authorized simulator connection.
     *
     * @param actor authenticated consumer
     * @param input exact event reminder configuration
     * @return new subscription
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Subscription create(Principal actor, Create input) {
        User user = actor.user();
        requireReady(user);
        if (input == null || !KIND.equals(input.eventKind()) || !validTitle(input.title())) {
            throw invalid();
        }
        window(input.timeZone(), input.quietHours());
        String membership = connector.lockEventAuthorization(actor, input.authorizationId());
        int count = jdbc.queryForObject("""
            SELECT COUNT(*) FROM assistant_sandbox_event_reminder WHERE account_id = ?
            """, Integer.class, user.id());
        if (count >= MAX_SUBSCRIPTIONS) {
            throw new AssistantApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED",
                "The event reminder limit has been reached.");
        }
        String id = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        QuietHours quiet = input.quietHours();
        jdbc.update("""
            INSERT INTO assistant_sandbox_event_reminder
                (id, account_id, workspace_id, authorization_id, title, time_zone,
                 quiet_start, quiet_end, active, config_epoch, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, TRUE, ?, ?, ?)
            """, id, user.id(), user.workspaceId(), input.authorizationId(), input.title().strip(),
            input.timeZone(), quiet == null ? null : quiet.start(),
            quiet == null ? null : quiet.end(),
            now, now, now);
        return expose(user, owned(user, membership, id));
    }

    /**
     * Changes scheduling consent for an owned subscription.
     *
     * @param actor authenticated owner
     * @param id reminder identifier
     * @param active desired activation state
     * @return updated subscription
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Subscription setActive(Principal actor, String id, boolean active) {
        User user = actor.user();
        String membership = connector.eventMembership(actor, true);
        Stored row = owned(user, membership, id);
        String state = state(user, row);
        if (REVOKED.equals(state)) {
            throw grantChanged();
        }
        if (active) {
            requireReady(user);
            connector.lockEventAuthorization(actor, row.authorization());
            if (!ACTIVE.equals(state)) {
                long now = System.currentTimeMillis();
                long epoch = Math.incrementExact(Math.max(now,
                    Math.max(row.epoch(), pauseCutoff(user, id))));
                jdbc.update("""
                    UPDATE assistant_sandbox_event_reminder
                    SET active = TRUE, config_epoch = ?, updated_at = ? WHERE id = ?
                    """, epoch, now, id);
            }
        } else if (ACTIVE.equals(state)) {
            erasure.pauseSandboxEventReminder(user, id, row.epoch());
        }
        return expose(user, owned(user, membership, id));
    }

    /**
     * Withdraws an owned event subscription and its retained in-app records.
     *
     * @param actor selected account
     * @param id reminder identifier
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void delete(Principal actor, String id) {
        User user = actor.user();
        requireUuid(id);
        String membership = connector.eventMembership(actor, true);
        if (deleted(user, id)) {
            return;
        }
        owned(user, membership, id);
        erasure.deleteSandboxEventReminder(user, id);
    }

    /**
     * Delivers or replays a verified event without synthesizing a connector effect.
     *
     * @param actor authenticated connector owner
     * @param event exact authorized action receipt identifier
     * @return scheduled, duplicate and paused counts for this event
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Receipt receive(Principal actor, Event event) {
        User user = actor.user();
        requireReady(user);
        if (event == null) {
            throw invalid();
        }
        var attested =
            connector.verifyEventReceipt(actor, event.authorizationId(), event.operationId());
        List<Stored> matching = jdbc.query(SUBSCRIPTIONS + """
            AND e.authorization_id = ? AND e.created_at <= ? ORDER BY e.id LIMIT 8
            """, SUBSCRIPTION_ROW, user.id(), user.workspaceId(), attested.membershipRevision(),
            event.authorizationId(), attested.recordedAt().toEpochMilli());
        int scheduled = 0;
        int duplicates = 0;
        int skipped = 0;
        Instant now = Instant.now();
        for (Stored subscription : matching) {
            if (deleted(user, subscription.id())) {
                continue;
            }
            String occurrenceId = occurrenceId(subscription.id(), event.operationId());
            if (!jdbc.queryForList("""
                SELECT id FROM assistant_sandbox_event_occurrence WHERE id = ?
                """, String.class, occurrenceId).isEmpty()) {
                duplicates++;
                continue;
            }
            if (ACTIVE.equals(state(user, subscription))) {
                insert(subscription, event.operationId(), occurrenceId, now, WAITING);
                scheduled++;
            } else {
                insert(subscription, event.operationId(), occurrenceId, now, SKIPPED);
                skipped++;
            }
        }
        return new Receipt(event.operationId(), scheduled, duplicates, skipped);
    }

    OccurrencePage occurrences(Principal actor, String id, int limit, String cursor) {
        if (limit < 1 || limit > 50 || cursor != null && !uuid(cursor)) {
            throw invalid();
        }
        User user = actor.user();
        String membership = connector.eventMembership(actor, false);
        var row = owned(user, membership, id);
        var rows = jdbc.query(OCCURRENCES + "AND id < ? ORDER BY id DESC LIMIT ?",
            OCCURRENCE_ROW, row.id(), cursor == null ? "z" : cursor, limit + 1);
        verifyRead(actor, membership);
        if (deleted(user, id)) {
            throw missing();
        }
        boolean sourceAvailable = !REVOKED.equals(state(user, row));
        var items = rows.stream().limit(limit).map(occurrence -> occurrence.expose(sourceAvailable))
            .toList();
        return new OccurrencePage(items, rows.size() <= limit ? null : items.get(limit - 1).id());
    }

    /**
     * Marks a delivered occurrence read within its owner scope.
     *
     * @param actor authenticated account
     * @param id reminder identifier
     * @param occurrenceId delivered occurrence
     * @return current occurrence record
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Occurrence read(Principal actor, String id, String occurrenceId) {
        requireUuid(occurrenceId);
        User user = actor.user();
        String membership = connector.eventMembership(actor, true);
        Stored row = owned(user, membership, id);
        OccurrenceRow occurrence = occurrence(id, occurrenceId);
        if (!DELIVERED.equals(occurrence.state())) {
            throw new AssistantApiException(HttpStatus.CONFLICT, "EVENT_REMINDER_NOT_DELIVERED",
                "Only a delivered event reminder can be marked read.");
        }
        if (occurrence.readAt() == null) {
            jdbc.update(
                """
                    UPDATE assistant_sandbox_event_occurrence SET read_at = ? WHERE id = ? AND read_at IS NULL
                    """,
                Math.max(System.currentTimeMillis(), occurrence.deliveredAt()), occurrenceId);
        }
        return occurrence(id, occurrenceId).expose(!REVOKED.equals(state(user, row)));
    }

    /**
     * Advances at most a bounded batch of authorized in-app event occurrences.
     */
    @Scheduled(fixedDelay = 750)
    public void tick() {
        advance(Instant.now());
    }

    void advance(Instant now) {
        if (!enabled || !journal.acceptsErasure() || journal.dispatchPaused()) {
            return;
        }
        List<Candidate> due = jdbc.query(
            """
                SELECT o.id, e.id AS reminder_id, e.account_id, e.workspace_id, m.membership_revision
                FROM assistant_sandbox_event_occurrence o
                JOIN assistant_sandbox_event_reminder e ON e.id = o.reminder_id
                JOIN assistant_sandbox_connector_grant g ON g.id = e.authorization_id
                JOIN assistant_workspace_member m ON m.account_id = e.account_id AND m.workspace_id = e.workspace_id
                WHERE o.state = 'waiting' AND o.delivery_at <= ? AND m.membership_revision = g.membership_revision
                ORDER BY o.delivery_at, o.id LIMIT 8
                """,
            (row, index) -> new Candidate(row.getString("id"), row.getString("reminder_id"),
                row.getString("account_id"), row.getString("workspace_id"),
                row.getString("membership_revision")),
            now.toEpochMilli());
        for (Candidate candidate : due) {
            transactions.executeWithoutResult(ignored -> publish(candidate, now));
        }
    }

    private void publish(Candidate candidate, Instant now) {
        if (journal.dispatchPaused() || jdbc.queryForList(
            """
                SELECT id FROM assistant_account WHERE id = ? AND deleted_at IS NULL FOR UPDATE SKIP LOCKED
                """,
            String.class, candidate.account()).isEmpty()) {
            return;
        }
        User owner = new User(candidate.account(), null, candidate.workspace(), null);
        if (!available(owner) || deleted(owner, candidate.reminder())) {
            return;
        }
        var selected = jdbc.query(SUBSCRIPTIONS + "AND e.id = ? FOR UPDATE",
            SUBSCRIPTION_ROW, owner.id(), owner.workspaceId(), candidate.membership(),
            candidate.reminder()).stream().findFirst();
        if (selected.isEmpty()) {
            return;
        }
        Stored row = selected.orElseThrow();
        OccurrenceRow item = occurrence(row.id(), candidate.id());
        if (!WAITING.equals(item.state()) || item.delivery() > now.toEpochMilli()) {
            return;
        }
        if (!ACTIVE.equals(state(owner, row)) || row.epoch() != item.epoch()
            || !connector.hasEventAuthorization(owner, row.authorization(), item.operation())) {
            finish(item.id(), WITHDRAWN, null);
            return;
        }
        Instant release = row.window().release(now);
        if (release.isAfter(now)) {
            jdbc.update(
                """
                    UPDATE assistant_sandbox_event_occurrence SET delivery_at = ? WHERE id = ? AND state = 'waiting'
                    """,
                release.toEpochMilli(), item.id());
        } else if (ReminderCalendar.expired(Instant.ofEpochMilli(item.delivery()), now)) {
            finish(item.id(), EXPIRED, null);
        } else {
            finish(item.id(), DELIVERED, now.toEpochMilli());
        }
    }

    private void finish(String id, String state, Long delivered) {
        jdbc.update("""
            UPDATE assistant_sandbox_event_occurrence SET state = ?, delivered_at = ?
            WHERE id = ? AND state = 'waiting'
            """, state, delivered, id);
    }

    private void insert(Stored subscription, String operation, String id, Instant now,
        String state) {
        long delivery = subscription.window().release(now).toEpochMilli();
        jdbc.update("""
            INSERT INTO assistant_sandbox_event_occurrence
                (id, reminder_id, operation_id, subscription_epoch, received_at, delivery_at, state)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """, id, subscription.id(), operation, subscription.epoch(), now.toEpochMilli(),
            delivery, state);
    }

    private Stored owned(User user, String membership, String id) {
        requireUuid(id);
        if (deleted(user, id)) {
            throw missing();
        }
        return jdbc.query(SUBSCRIPTIONS + "AND e.id = ?", SUBSCRIPTION_ROW, user.id(),
            user.workspaceId(), membership, id).stream().findFirst()
            .orElseThrow(AssistantEventReminders::missing);
    }

    private OccurrenceRow occurrence(String reminder, String id) {
        return jdbc.query(OCCURRENCES + "AND id = ?", OCCURRENCE_ROW, reminder, id).stream()
            .findFirst().orElseThrow(AssistantEventReminders::missing);
    }

    private Subscription expose(User owner, Stored row) {
        return new Subscription(row.id(), row.title(), row.authorization(), KIND, row.zone(),
            row.quiet() == null ? null : new QuietHours(row.quiet(), row.quietEnd()),
            state(owner, row),
            Instant.ofEpochMilli(row.created()), Instant.ofEpochMilli(row.updated()));
    }

    private String state(User owner, Stored row) {
        if (row.revoked() || journal.sandboxAuthorizationRevoked(owner.id(), owner.workspaceId(),
            row.authorization())) {
            return REVOKED;
        }
        return row.active() && row.epoch() > pauseCutoff(owner, row.id()) ? ACTIVE : PAUSED;
    }

    private long pauseCutoff(User owner, String id) {
        return journal.sandboxEventPauseCutoff(owner.id(), owner.workspaceId(), id);
    }

    private boolean deleted(User owner, String id) {
        return journal.sandboxEventReminderDeleted(owner.id(), owner.workspaceId(), id);
    }

    private void requireReady(User owner) {
        if (!available(owner)) {
            throw new AssistantApiException(HttpStatus.FORBIDDEN, "CAPABILITY_UNAVAILABLE",
                "Simulator event reminders are unavailable.");
        }
    }

    private void verifyRead(Principal actor, String membership) {
        if (!membership.equals(connector.eventMembership(actor, false))) {
            throw missing();
        }
    }

    private static ReminderCalendar.QuietWindow window(String zone, QuietHours quiet) {
        try {
            if (quiet != null && (quiet.start() == null || quiet.end() == null)) {
                throw invalid();
            }
            return ReminderCalendar.quietWindow(zone, quiet == null ? null : quiet.start(),
                quiet == null ? null : quiet.end());
        } catch (DateTimeException | IllegalArgumentException error) {
            throw invalid();
        }
    }

    private static boolean validTitle(String title) {
        return title != null && !title.isBlank() && title.codePointCount(0, title.length()) <= 200
            && title.codePoints().noneMatch(Character::isISOControl);
    }

    static String occurrenceId(String subscription, String operation) {
        return UUID
            .nameUUIDFromBytes(("assistant.event-reminder.v1:" + subscription + ":" + operation)
                .getBytes(StandardCharsets.US_ASCII))
            .toString();
    }

    private static void requireUuid(String id) {
        if (!uuid(id)) {
            throw invalid();
        }
    }

    private static boolean uuid(String value) {
        try {
            return value != null && UUID.fromString(value).toString().equals(value);
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static AssistantApiException invalid() {
        return new AssistantApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
            "A valid event, reminder title, time zone and quiet window are required.");
    }

    private static AssistantApiException missing() {
        return new AssistantApiException(HttpStatus.NOT_FOUND, "NOT_FOUND",
            "The scoped event reminder was not found.");
    }

    private static AssistantApiException grantChanged() {
        return new AssistantApiException(HttpStatus.CONFLICT, "CONNECTOR_AUTHORIZATION_CHANGED",
            "The connection authorization changed.");
    }

    /**
     * Configuration confirmed by the owner.
     *
     * @param title in-app reminder title
     * @param authorizationId active connector grant
     * @param eventKind fixed event kind
     * @param timeZone IANA time zone
     * @param quietHours optional quiet window
     */
    public record Create(String title, String authorizationId, String eventKind, String timeZone,
        QuietHours quietHours) {
    }

    /**
     * Bound event envelope identifying an existing durable simulator receipt.
     *
     * @param authorizationId current simulator grant
     * @param operationId completed approved operation
     */
    public record Event(String authorizationId, String operationId) {
    }

    /**
     * Scoped event reminder.
     *
     * @param id reminder identifier
     * @param title local title
     * @param authorizationId bound connector grant
     * @param eventKind supported event
     * @param timeZone configured IANA zone
     * @param quietHours optional quiet window
     * @param state current effective subscription state
     * @param createdAt original creation
     * @param updatedAt last policy update
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Subscription(String id, String title, String authorizationId, String eventKind,
        String timeZone, QuietHours quietHours, String state, Instant createdAt,
        Instant updatedAt) {
    }

    /**
     * Bounded list of owned subscriptions.
     *
     * @param items currently visible subscriptions
     */
    public record SubscriptionList(List<Subscription> items) {
    }

    /**
     * Delivery acceptance and deduplication counts for this event.
     *
     * @param operationId existing simulator operation
     * @param scheduled new active occurrences
     * @param duplicates occurrences recorded earlier
     * @param skipped occurrences received during pause
     */
    public record Receipt(String operationId, int scheduled, int duplicates, int skipped) {
    }

    /**
     * One scoped in-app notification outcome.
     *
     * @param id occurrence identifier
     * @param operationId source only while authorized
     * @param state current outcome
     * @param receivedAt first delivery receipt time
     * @param deliveryAt planned in-app delivery
     * @param deliveredAt completed delivery time
     * @param readAt when the owner marked it read
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Occurrence(String id, String operationId, String state, Instant receivedAt,
        Instant deliveryAt, Instant deliveredAt, Instant readAt) {
    }

    /**
     * Bounded occurrence page.
     *
     * @param items current authorized records
     * @param nextCursor continuation or null
     */
    public record OccurrencePage(List<Occurrence> items,
        @JsonInclude(JsonInclude.Include.ALWAYS) String nextCursor) {
    }

    private record Stored(String id, String title, String authorization, String zone, String quiet,
        String quietEnd, boolean active, long epoch, long created, long updated, boolean revoked) {

        ReminderCalendar.QuietWindow window() {
            return ReminderCalendar.quietWindow(zone, quiet, quietEnd);
        }
    }

    private record OccurrenceRow(String id, String operation, String state, long epoch,
        long received, long delivery, Long deliveredAt, Long readAt) {

        Occurrence expose(boolean sourceAvailable) {
            return new Occurrence(id, sourceAvailable ? operation : null, state,
                Instant.ofEpochMilli(received), Instant.ofEpochMilli(delivery),
                deliveredAt == null ? null : Instant.ofEpochMilli(deliveredAt),
                readAt == null ? null : Instant.ofEpochMilli(readAt));
        }
    }

    private record Candidate(String id, String reminder, String account, String workspace,
        String membership) {
    }
}

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
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import top.continew.admin.assistant.account.AssistantAccounts;
import top.continew.admin.assistant.account.AssistantAccounts.Principal;
import top.continew.admin.assistant.account.AssistantAccounts.User;
import top.continew.admin.assistant.account.AssistantApiException;
import top.continew.admin.assistant.privacy.AssistantDeletionJournal;
import top.continew.admin.assistant.privacy.AssistantErasure;
import top.continew.admin.assistant.reminder.AssistantReminders.QuietHours;

import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Presents bounded, opted-in local suggestions from currently authorized calendar facts.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
@Service
@Slf4j
@ConditionalOnProperty(name = "assistant.enabled", havingValue = "true")
public class AssistantProactiveAssistant {

    private static final String REVIEW = "reminder_review";
    private static final String PLAN = "schedule_planner";
    private static final String IN_APP = "in_app";
    private static final String SILENT = "silent";
    private static final Duration SOURCE_HISTORY = Duration.ofDays(7);
    private static final Duration SOURCE_HORIZON = Duration.ofHours(24);
    private static final Duration NEXT_CHECK = Duration.ofSeconds(30);
    private static final Duration FAILURE_DELAY = Duration.ofMinutes(1);
    private static final Duration RETENTION = Duration.ofDays(14);
    private static final String PREFERENCES_SQL = """
        SELECT membership_revision, enabled, role, notification, time_zone, quiet_start, quiet_end,
            policy_epoch, updated_at, next_check_at
        FROM assistant_proactive_preference WHERE account_id = ? AND workspace_id = ?
        """;
    private static final String SUGGESTIONS_SQL =
        """
            SELECT id, role, reminder_id, occurrence_id, source_epoch AS config_epoch, slot_day, created_at
            FROM assistant_proactive_suggestion
            WHERE account_id = ? AND workspace_id = ? AND membership_revision = ?
                AND policy_epoch = ? AND local_day = ? AND dismissed = FALSE
            """;
    private static final String REVIEW_SOURCE = """
        SELECT r.id AS reminder_id, o.id AS occurrence_id, r.config_epoch, NULL AS slot_day
        FROM assistant_reminder_occurrence o JOIN assistant_reminder r ON r.id = o.reminder_id
        WHERE r.account_id = ? AND r.workspace_id = ? AND o.config_epoch = r.config_epoch
          AND o.outcome = 'delivered' AND o.read_at IS NULL AND o.occurred_at >= ?
        """;
    private static final String PLAN_SOURCE =
        """
            SELECT r.id AS reminder_id, NULL AS occurrence_id, r.config_epoch, r.next_slot_day AS slot_day
            FROM assistant_reminder r
            WHERE r.account_id = ? AND r.workspace_id = ? AND r.status = 'active'
                AND r.next_wake_at > ? AND r.next_wake_at <= ?
            """;
    private static final RowMapper<Stored> PREF_ROW = (row, index) -> new Stored(
        row.getString("membership_revision"), row.getBoolean("enabled"), row.getString("role"),
        row.getString("notification"), row.getString("time_zone"), row.getString("quiet_start"),
        row.getString("quiet_end"), row.getLong("policy_epoch"), row.getLong("updated_at"),
        row.getObject("next_check_at", Long.class));
    private static final RowMapper<SuggestionRow> SUGGESTION_ROW =
        (row, index) -> new SuggestionRow(
            row.getString("id"), row.getString("role"), readSource(row), row.getLong("created_at"));
    private static final RowMapper<Source> SOURCE_ROW = (row, index) -> readSource(row);
    private static final Preferences DEFAULT =
        new Preferences(false, REVIEW, SILENT, "UTC", null, null);

    private final JdbcTemplate jdbc;
    private final AssistantAccounts accounts;
    private final AssistantReminders reminders;
    private final AssistantDeletionJournal journal;
    private final AssistantErasure erasure;
    private final TransactionTemplate transactions;
    private final boolean enabled;
    private final AtomicLong nextSchedulerWarning = new AtomicLong();

    /**
     * Connects this opt-in feature to the private calendar and independent policy log.
     *
     * @param jdbc assistant database
     * @param accounts authenticated consumers and workspace membership
     * @param reminders local calendar availability
     * @param journal independent policy and source guards
     * @param erasure durable owner consent changes
     * @param manager database transaction manager
     * @param environment deployment profiles
     * @param enabled explicit local feature flag
     */
    public AssistantProactiveAssistant(JdbcTemplate jdbc, AssistantAccounts accounts,
        AssistantReminders reminders, AssistantDeletionJournal journal, AssistantErasure erasure,
        PlatformTransactionManager manager, Environment environment,
        @Value("${assistant.proactive.local.enabled:false}") boolean enabled) {
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.reminders = reminders;
        this.journal = journal;
        this.erasure = erasure;
        this.enabled = enabled;
        transactions = new TransactionTemplate(manager);
        transactions.setIsolationLevel(TransactionTemplate.ISOLATION_READ_COMMITTED);
        List<String> profiles = Arrays.asList(environment.getActiveProfiles());
        if (enabled && (profiles.contains("prod")
            || !profiles.contains("dev") && !profiles.contains("test"))) {
            throw new IllegalStateException(
                "The proactive calendar fixture requires a local test profile.");
        }
    }

    /**
     * Reports availability without subscribing an account to suggestions.
     *
     * @param user signed-in workspace member
     * @return whether explicit opt-in is possible
     */
    public boolean available(User user) {
        return ready() && user != null && member(user.id(), user.workspaceId(), false).isPresent();
    }

    Preferences preferences(Principal actor) {
        Scope scope = scope(actor, false);
        var stored = current(scope);
        verifyRead(scope);
        return stored.map(row -> expose(scope, row)).orElse(DEFAULT);
    }

    /**
     * Saves one explicit set of preferences and retires the previous enabled policy.
     *
     * @param actor signed-in owner
     * @param input exact opt-in and current settings
     * @return current saved preferences
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Preferences update(Principal actor, Settings input) {
        validate(input);
        Scope scope = scope(actor, true);
        if (input.enabled() && !ready()) {
            throw unavailable();
        }
        var previous = stored(scope);
        var visible = previous.filter(row -> row.membership().equals(scope.membership()));
        var prior = visible.map(row -> expose(scope, row)).orElse(DEFAULT);
        if (same(prior, input)) {
            return prior;
        }
        if (visible.isPresent() && prior.enabled()) {
            erasure.revokeProactivePreferences(actor.user(), visible.orElseThrow().epoch());
        }
        long now = System.currentTimeMillis();
        long epoch = Math.incrementExact(Math.max(now, Math.max(
            previous.map(Stored::epoch).orElse(0L), cutoff(scope))));
        QuietHours quiet = input.quietHours();
        Object[] values = {
            scope.membership(), input.enabled(), input.role(), input.notification(),
            input.timeZone(),
            quiet == null ? null : quiet.start(), quiet == null ? null : quiet.end(), epoch, now,
            input.enabled() && IN_APP.equals(input.notification()) ? now : null,
            scope.account(), scope.workspace()
        };
        if (previous.isPresent()) {
            jdbc.update(
                """
                    UPDATE assistant_proactive_preference
                    SET membership_revision = ?, enabled = ?, role = ?, notification = ?, time_zone = ?,
                        quiet_start = ?, quiet_end = ?, policy_epoch = ?, updated_at = ?, next_check_at = ?
                    WHERE account_id = ? AND workspace_id = ?
                    """,
                values);
        } else {
            jdbc.update("""
                INSERT INTO assistant_proactive_preference
                    (membership_revision, enabled, role, notification, time_zone, quiet_start,
                     quiet_end, policy_epoch, updated_at, next_check_at, account_id, workspace_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, values);
        }
        return new Preferences(input.enabled(), input.role(), input.notification(),
            input.timeZone(),
            input.quietHours(), Instant.ofEpochMilli(now));
    }

    Suggestions suggestions(Principal actor) {
        Scope scope = scope(actor, false);
        var stored = current(scope);
        Instant now = Instant.now();
        if (stored.isEmpty() || !permitted(scope, stored.orElseThrow(), now)) {
            return new Suggestions(List.of());
        }
        Stored policy = stored.orElseThrow();
        var rows = jdbc.query(SUGGESTIONS_SQL + "ORDER BY created_at DESC, id DESC LIMIT 2",
            SUGGESTION_ROW, scope.account(), scope.workspace(), scope.membership(), policy.epoch(),
            localDay(policy, now));
        verifyRead(scope);
        var latest = current(scope).filter(row -> row.epoch() == policy.epoch()
            && permitted(scope, row, Instant.now()));
        if (latest.isEmpty()) {
            return new Suggestions(List.of());
        }
        var items = rows.stream().filter(row -> !journal.proactiveSuggestionDismissed(
            scope.account(), scope.workspace(), row.id()) && stillSource(scope, row, now))
            .map(SuggestionRow::expose).toList();
        verifyRead(scope);
        return permitted(scope, policy, Instant.now()) ? new Suggestions(items)
            : new Suggestions(List.of());
    }

    /**
     * Durably deactivates an owned suggestion, including in earlier backups.
     *
     * @param actor signed-in owner
     * @param id exact suggestion
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void dismiss(Principal actor, String id) {
        requireUuid(id);
        Scope scope = scope(actor, true);
        var rows = jdbc.queryForList("""
            SELECT id FROM assistant_proactive_suggestion
            WHERE id = ? AND account_id = ? AND workspace_id = ? AND membership_revision = ?
            """, String.class, id, scope.account(), scope.workspace(), scope.membership());
        if (rows.isEmpty()) {
            throw missing();
        }
        if (!journal.proactiveSuggestionDismissed(scope.account(), scope.workspace(), id)) {
            erasure.dismissProactiveSuggestion(actor.user(), id);
        }
    }

    /**
     * Produces only bounded calendar suggestions outside the current quiet window.
     */
    @Scheduled(fixedDelay = 1000)
    public void tick() {
        advance(Instant.now());
    }

    void advance(Instant now) {
        if (!ready() || journal.dispatchPaused()) {
            return;
        }
        var elected = jdbc.query(
            """
                SELECT p.account_id, p.workspace_id, p.membership_revision, p.policy_epoch, p.next_check_at
                FROM assistant_proactive_preference p
                JOIN assistant_workspace_member m ON m.account_id = p.account_id AND m.workspace_id = p.workspace_id
                    AND m.membership_revision = p.membership_revision
                WHERE p.enabled = TRUE AND p.notification = 'in_app' AND p.next_check_at <= ?
                ORDER BY p.next_check_at, p.account_id, p.workspace_id LIMIT 8
                """,
            (row, index) -> new Due(
                new Scope(row.getString("account_id"), row.getString("workspace_id"),
                    row.getString("membership_revision")),
                row.getLong("policy_epoch"),
                row.getLong("next_check_at")),
            now.toEpochMilli());
        for (Due candidate : elected) {
            if (journal.dispatchPaused()) {
                return;
            }
            try {
                transactions.executeWithoutResult(ignored -> produce(candidate, now));
            } catch (DataAccessException failure) {
                warnSchedulerFailure(failure, now);
                deferFailure(candidate, now);
            }
        }
    }

    private void deferFailure(Due candidate, Instant now) {
        if (journal.dispatchPaused()) {
            return;
        }
        try {
            transactions.executeWithoutResult(ignored -> currentDue(candidate, now)
                .ifPresent(policy -> schedule(candidate.scope(), policy, now.plus(FAILURE_DELAY))));
        } catch (DataAccessException failure) {
            warnSchedulerFailure(failure, now);
        }
    }

    private void warnSchedulerFailure(DataAccessException failure, Instant now) {
        long previous = nextSchedulerWarning.get();
        if (now.toEpochMilli() >= previous
            && nextSchedulerWarning.compareAndSet(previous,
                now.plus(FAILURE_DELAY).toEpochMilli())) {
            log.warn("The local proactive scheduler encountered a storage failure ({})",
                failure.getClass().getSimpleName());
        }
    }

    private Optional<Stored> currentDue(Due candidate, Instant now) {
        Scope scope = candidate.scope();
        if (!ready() || journal.dispatchPaused()
            || jdbc.queryForList(
                """
                    SELECT id FROM assistant_account WHERE id = ? AND deleted_at IS NULL FOR UPDATE SKIP LOCKED
                    """,
                String.class, scope.account()).isEmpty()
            || member(scope.account(), scope.workspace(), true).filter(scope::equals)
                .isEmpty()) {
            return Optional.empty();
        }
        return current(scope).filter(policy -> effective(scope, policy)
            && IN_APP.equals(policy.notification()) && policy.nextCheck() != null
            && policy.epoch() == candidate.epoch() && policy.nextCheck() == candidate.scheduledAt()
            && candidate.scheduledAt() <= now.toEpochMilli());
    }

    private void produce(Due due, Instant now) {
        var current = currentDue(due, now);
        if (current.isEmpty()) {
            return;
        }
        Scope candidate = due.scope();
        Stored policy = current.orElseThrow();
        Instant release = policy.window().release(now);
        if (release.isAfter(now)) {
            schedule(candidate, policy, release);
            return;
        }
        long day = localDay(policy, now);
        String id = suggestionId(candidate, policy.role(), day);
        if (journal.proactiveSuggestionIssued(candidate.account(), candidate.workspace(), id)
            || hasSuggestion(candidate, policy.role(), day)) {
            schedule(candidate, policy, nextDay(policy, day));
            return;
        }
        var source = pickSource(candidate, policy.role(), now);
        if (source.isEmpty()) {
            schedule(candidate, policy, now.plus(NEXT_CHECK));
            return;
        }
        erasure.reserveProactiveSuggestion(
            new User(candidate.account(), null, candidate.workspace(), null), id);
        writeSuggestion(candidate, policy, source.orElseThrow(), id, day, now);
        jdbc.update("""
            DELETE FROM assistant_proactive_suggestion WHERE account_id = ? AND workspace_id = ?
                AND created_at < ?
            """, candidate.account(), candidate.workspace(), now.minus(RETENTION).toEpochMilli());
        schedule(candidate, policy, nextDay(policy, day));
    }

    private boolean hasSuggestion(Scope scope, String role, long day) {
        return !jdbc.queryForList("""
            SELECT id FROM assistant_proactive_suggestion
            WHERE account_id = ? AND workspace_id = ? AND membership_revision = ?
                AND role = ? AND local_day = ?
            """, String.class, scope.account(), scope.workspace(), scope.membership(), role, day)
            .isEmpty();
    }

    private void writeSuggestion(Scope scope, Stored policy, Source source, String id, long day,
        Instant now) {
        jdbc.update("""
            INSERT INTO assistant_proactive_suggestion
                (id, account_id, workspace_id, membership_revision, policy_epoch, role, local_day,
                 reminder_id, occurrence_id, source_epoch, slot_day, created_at, dismissed)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, FALSE)
            """, id, scope.account(), scope.workspace(), scope.membership(),
            policy.epoch(), policy.role(), day, source.reminder(), source.occurrence(),
            source.epoch(), source.slot(), now.toEpochMilli());
    }

    private void schedule(Scope scope, Stored policy, Instant next) {
        jdbc.update(
            """
                UPDATE assistant_proactive_preference SET next_check_at = ?
                WHERE account_id = ? AND workspace_id = ? AND membership_revision = ? AND policy_epoch = ?
                    AND enabled = TRUE AND notification = 'in_app' AND next_check_at = ?
                """,
            next.toEpochMilli(), scope.account(), scope.workspace(), scope.membership(),
            policy.epoch(), policy.nextCheck());
    }

    private Optional<Source> pickSource(Scope scope, String role, Instant now) {
        List<Source> rows = REVIEW.equals(role)
            ? jdbc.query(REVIEW_SOURCE + "ORDER BY o.occurred_at DESC, o.id LIMIT 16", SOURCE_ROW,
                scope.account(), scope.workspace(), now.minus(SOURCE_HISTORY).toEpochMilli())
            : jdbc.query(PLAN_SOURCE + "ORDER BY r.next_wake_at, r.id LIMIT 16", SOURCE_ROW,
                scope.account(), scope.workspace(), now.toEpochMilli(),
                now.plus(SOURCE_HORIZON).toEpochMilli());
        return rows.stream().filter(source -> permittedSource(scope, source)).findFirst();
    }

    private boolean stillSource(Scope scope, SuggestionRow row, Instant now) {
        Source source = row.source();
        if (!permittedSource(scope, source)) {
            return false;
        }
        List<Source> current = REVIEW.equals(row.role())
            ? jdbc.query(REVIEW_SOURCE + "AND r.id = ? AND o.id = ?", SOURCE_ROW,
                scope.account(), scope.workspace(), now.minus(SOURCE_HISTORY).toEpochMilli(),
                source.reminder(), source.occurrence())
            : jdbc.query(PLAN_SOURCE + "AND r.id = ?", SOURCE_ROW, scope.account(),
                scope.workspace(),
                now.toEpochMilli(), now.plus(SOURCE_HORIZON).toEpochMilli(), source.reminder());
        return current.stream().anyMatch(source::equals) && permittedSource(scope, source);
    }

    private boolean permittedSource(Scope scope, Source source) {
        return !journal.blocksReminder(scope.account(), scope.workspace(), source.reminder())
            && source.epoch() > journal.reminderPauseCutoff(scope.account(), scope.workspace(),
                source.reminder());
    }

    private Optional<Stored> current(Scope scope) {
        return stored(scope).filter(row -> row.membership().equals(scope.membership()));
    }

    private Optional<Stored> stored(Scope scope) {
        return jdbc.query(PREFERENCES_SQL, PREF_ROW, scope.account(), scope.workspace())
            .stream().findFirst();
    }

    private boolean permitted(Scope scope, Stored policy, Instant now) {
        return ready() && effective(scope, policy) && IN_APP.equals(policy.notification())
            && !policy.window().release(now).isAfter(now);
    }

    private boolean effective(Scope scope, Stored row) {
        return row.enabled() && row.epoch() > cutoff(scope);
    }

    private long cutoff(Scope scope) {
        return journal.proactivePreferenceCutoff(scope.account(), scope.workspace());
    }

    private boolean ready() {
        return enabled && journal.acceptsErasure() && reminders.available();
    }

    private Scope scope(Principal actor, boolean lock) {
        User user = actor.user();
        if (lock) {
            accounts.lockActive(user.id());
            if (jdbc
                .queryForList(
                    """
                        SELECT id FROM assistant_session WHERE id = ? AND account_id = ? AND expires_at > ? FOR UPDATE
                        """,
                    String.class, actor.sessionId(), user.id(), System.currentTimeMillis())
                .isEmpty()) {
                throw new AssistantApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED",
                    "The active session is required.");
            }
        }
        return member(user.id(), user.workspaceId(), lock)
            .orElseThrow(AssistantProactiveAssistant::missing);
    }

    private Optional<Scope> member(String account, String workspace, boolean lock) {
        if (!journal.acceptsErasure() || journal.blocksAccount(account)) {
            return Optional.empty();
        }
        return jdbc
            .query(
                """
                    SELECT membership_revision FROM assistant_workspace_member WHERE account_id = ? AND workspace_id = ?
                    """
                    + (lock ? " FOR UPDATE" : ""),
                (row, index) -> new Scope(account, workspace,
                    row.getString("membership_revision")),
                account, workspace)
            .stream()
            .filter(
                scope -> !journal.blocksWorkspaceMembership(account, workspace, scope.membership()))
            .findFirst();
    }

    private void verifyRead(Scope scope) {
        if (member(scope.account(), scope.workspace(), false).filter(scope::equals).isEmpty()) {
            throw missing();
        }
    }

    private Preferences expose(Scope scope, Stored row) {
        QuietHours quiet =
            row.quietStart() == null ? null : new QuietHours(row.quietStart(), row.quietEnd());
        return new Preferences(effective(scope, row), row.role(), row.notification(), row.zone(),
            quiet,
            Instant.ofEpochMilli(row.updated()));
    }

    private static boolean same(Preferences prior, Settings input) {
        return prior.enabled() == input.enabled() && prior.role().equals(input.role())
            && prior.notification().equals(input.notification())
            && prior.timeZone().equals(input.timeZone())
            && Objects.equals(prior.quietHours(), input.quietHours());
    }

    private static void validate(Settings input) {
        if (input == null || !(REVIEW.equals(input.role()) || PLAN.equals(input.role()))
            || !(IN_APP.equals(input.notification()) || SILENT.equals(input.notification()))) {
            throw invalid();
        }
        QuietHours quiet = input.quietHours();
        if (quiet != null && (quiet.start() == null || quiet.end() == null)) {
            throw invalid();
        }
        try {
            ReminderCalendar.quietWindow(input.timeZone(), quiet == null ? null : quiet.start(),
                quiet == null ? null : quiet.end());
        } catch (DateTimeException | IllegalArgumentException error) {
            throw invalid();
        }
    }

    private static String suggestionId(Scope scope, String role, long day) {
        String key = "assistant.proactive.v1:" + scope.account() + ":" + scope.workspace()
            + ":" + scope.membership() + ":" + role + ":" + day;
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.US_ASCII)).toString();
    }

    private static long localDay(Stored policy, Instant at) {
        return at.atZone(policy.window().zone()).toLocalDate().toEpochDay();
    }

    private static Instant nextDay(Stored policy, long day) {
        return LocalDate.ofEpochDay(day).plusDays(1).atStartOfDay(policy.window().zone())
            .toInstant();
    }

    private static Source readSource(java.sql.ResultSet row) throws java.sql.SQLException {
        long slot = row.getLong("slot_day");
        Long slotDay = row.wasNull() ? null : slot;
        return new Source(row.getString("reminder_id"), row.getString("occurrence_id"),
            row.getLong("config_epoch"), slotDay);
    }

    private static void requireUuid(String id) {
        try {
            if (id == null || !UUID.fromString(id).toString().equals(id)) {
                throw invalid();
            }
        } catch (IllegalArgumentException error) {
            throw invalid();
        }
    }

    private static AssistantApiException invalid() {
        return new AssistantApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
            "Choose a valid role, notification policy, IANA time zone and quiet window.");
    }

    private static AssistantApiException unavailable() {
        return new AssistantApiException(HttpStatus.FORBIDDEN, "CAPABILITY_UNAVAILABLE",
            "Local proactive calendar suggestions are unavailable.");
    }

    private static AssistantApiException missing() {
        return new AssistantApiException(HttpStatus.NOT_FOUND, "NOT_FOUND",
            "The proactive calendar scope or suggestion was not found.");
    }

    /**
     * Explicit configuration for local suggestions.
     *
     * @param enabled user's opt-in
     * @param role selected calendar suggestion rule
     * @param notification in-app or silent
     * @param timeZone selected IANA time zone
     * @param quietHours optional local quiet period
     */
    public record Settings(boolean enabled, String role, String notification, String timeZone,
        QuietHours quietHours) {
    }

    /**
     * Current preferences for the owner's selected workspace.
     *
     * @param enabled current effective opt-in
     * @param role selected rule
     * @param notification notification mode
     * @param timeZone current time zone
     * @param quietHours optional quiet period
     * @param updatedAt most recent explicit update
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Preferences(boolean enabled, String role, String notification, String timeZone,
        QuietHours quietHours, Instant updatedAt) {
    }

    /**
     * One approved local suggestion without private source content.
     *
     * @param id opaque suggestion ID
     * @param kind review category
     * @param state current authorization state
     * @param source generic owned calendar provenance
     * @param createdAt time generated
     */
    public record Suggestion(String id, String kind, String state, String source,
        Instant createdAt) {
    }

    /**
     * Suggestions currently eligible to display.
     *
     * @param items owned calendar suggestions
     */
    public record Suggestions(List<Suggestion> items) {
    }

    private record Scope(String account, String workspace, String membership) {
    }

    private record Due(Scope scope, long epoch, long scheduledAt) {
    }

    private record Stored(String membership, boolean enabled, String role, String notification,
        String zone, String quietStart, String quietEnd, long epoch, long updated, Long nextCheck) {

        ReminderCalendar.QuietWindow window() {
            return ReminderCalendar.quietWindow(zone, quietStart, quietEnd);
        }
    }

    private record Source(String reminder, String occurrence, long epoch, Long slot) {
    }

    private record SuggestionRow(String id, String role, Source source, long created) {

        Suggestion expose() {
            return new Suggestion(id, REVIEW.equals(role) ? "review_reminders" : "review_upcoming",
                "available", "owned_calendar", Instant.ofEpochMilli(created));
        }
    }
}

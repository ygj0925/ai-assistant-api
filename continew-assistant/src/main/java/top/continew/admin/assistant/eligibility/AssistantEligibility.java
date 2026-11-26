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

package top.continew.admin.assistant.eligibility;

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
import top.continew.admin.assistant.privacy.AssistantDeletionJournal;
import top.continew.admin.assistant.privacy.AssistantErasure;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Models synthetic eligibility and guardian consent for a single explicitly assigned workspace.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
@Service
@ConditionalOnProperty(name = "assistant.enabled", havingValue = "true")
public class AssistantEligibility {

    private static final String ALLOWED = "allowed";
    private static final String GUARDIAN_REQUIRED = "guardian_required";
    private static final String OUTSIDE_REGION = "outside_fixture_region";
    private static final String EXPIRED = "consent_expired";
    private static final String NOT_ENROLLED = "not_enrolled";
    private static final int MAX_GUARDIAN_LINKS = 16;
    private static final int MAX_ENROLLMENTS = 64;
    private static final Admission UNRESTRICTED = new Admission(null, ALLOWED);
    private static final Admission HELD = new Admission(null, NOT_ENROLLED);
    private static final String ENROLLMENTS = """
        SELECT id, workspace_id, participant_account_id, guardian_account_id, participant_revision,
            guardian_revision, scenario, consent_revision, consent_until
        FROM assistant_eligibility_enrollment WHERE workspace_id = ?
        """;
    private static final RowMapper<Enrollment> ENROLLMENT_ROW = (row, index) -> new Enrollment(
        row.getString("id"), row.getString("workspace_id"), row.getString("participant_account_id"),
        row.getString("guardian_account_id"), row.getString("participant_revision"),
        row.getString("guardian_revision"), row.getString("scenario"),
        row.getString("consent_revision"),
        row.getObject("consent_until", Long.class));

    private final JdbcTemplate jdbc;
    private final AssistantAccounts accounts;
    private final AssistantDeletionJournal journal;
    private final AssistantErasure erasure;
    private final boolean enabled;
    private final String fixtureWorkspace;
    private final long ttlMillis;

    /**
     * Wires configured synthetic scope and short-lived consent.
     *
     * @param jdbc exact account and consent scope queries
     * @param accounts current consumer identity locks
     * @param journal independent consent revocation and membership proof
     * @param erasure durable consent retirement transaction
     * @param enabled display, enrollment, and affirmative consent controls
     * @param fixtureWorkspace one explicitly designated synthetic workspace
     * @param ttlSeconds fixed consent horizon in seconds
     */
    public AssistantEligibility(JdbcTemplate jdbc, AssistantAccounts accounts,
        AssistantDeletionJournal journal, AssistantErasure erasure,
        @Value("${assistant.eligibility.enabled:false}") boolean enabled,
        @Value("${assistant.eligibility.fixture-workspace-id:}") String fixtureWorkspace,
        @Value("${assistant.eligibility.consent-duration-seconds:900}") int ttlSeconds) {
        if ((enabled || !fixtureWorkspace.isBlank()) && !uuid(fixtureWorkspace)
            || ttlSeconds < 1 || ttlSeconds > 86_400) {
            throw new IllegalArgumentException(
                "Invalid synthetic eligibility scope or consent duration.");
        }
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.journal = journal;
        this.erasure = erasure;
        this.enabled = enabled;
        this.fixtureWorkspace = fixtureWorkspace;
        this.ttlMillis = ttlSeconds * 1000L;
    }

    /**
     * Advertises synthetic controls only to one currently enrolled participant or guardian.
     *
     * @param user current authenticated selected scope
     * @return whether that actor may see pilot controls
     */
    public boolean availableIn(User user) {
        if (!enabled || user == null || !pilot(user.workspaceId()) || !journal.acceptsErasure()) {
            return false;
        }
        return participant(user.id(), user.workspaceId(), false).filter(this::valid).isPresent()
            || guardianRows(user).stream().anyMatch(this::valid);
    }

    /**
     * Reports the current actor's connector eligibility without disclosing another identity.
     *
     * @param actor live consumer session
     * @return safe status for the selected workspace
     */
    public Status status(Principal actor) {
        User user = actor.user();
        Admission current = connector(user.id(), user.workspaceId(), false);
        return new Status(pilot(user.workspaceId()), current.connectorStatus());
    }

    /**
     * Lists only a current guardian's exact consent records, with opaque enrollment IDs.
     *
     * @param actor live guardian session
     * @return bounded own review records
     */
    public Consents guardian(Principal actor) {
        User guardian = actor.user();
        requirePilot(guardian.workspaceId());
        if (!enabled || membership(guardian.id(), guardian.workspaceId()).isEmpty()) {
            throw forbidden();
        }
        return new Consents(guardianRows(guardian).stream().filter(this::valid)
            .map(this::view).toList());
    }

    /**
     * Confirms or withdraws one exact current guardian consent; a repeat cannot extend its lifetime.
     *
     * @param actor live guardian session
     * @param id opaque enrollment identifier
     * @param revision exact reviewed consent generation
     * @param authorize requested consent decision
     * @param confirm affirmative confirmation of that decision
     * @return current bounded review state
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Consent decide(Principal actor, String id, String revision, boolean authorize,
        boolean confirm) {
        User guardian = actor.user();
        requirePilot(guardian.workspaceId());
        if (!confirm || !uuid(id) || !uuid(revision)) {
            throw invalid();
        }
        if (authorize && !enabled) {
            throw unavailable();
        }
        Enrollment initial =
            guardianRow(guardian, id, false).orElseThrow(AssistantEligibility::missing);
        lockParties(guardian.id(), initial.participant());
        requireSession(actor);
        Enrollment current = guardianRow(guardian, id, true)
            .filter(this::valid).orElseThrow(AssistantEligibility::missing);
        if (!current.revision().equals(revision)) {
            throw conflict("The consent changed. Review the current request.");
        }
        if (authorize && OUTSIDE_REGION.equals(current.scenario())) {
            throw denied();
        }
        Admission before = effective(current);
        if (authorize && before.allowed() || !authorize && current.until() == null) {
            return view(current);
        }

        long now = System.currentTimeMillis();
        Long until = authorize ? now + ttlMillis : null;
        erasure.retireGuardianConsent(current.participant(), current.workspace(), revision);
        String next = UUID.randomUUID().toString();
        jdbc.update("""
            UPDATE assistant_eligibility_enrollment
            SET consent_revision = ?, consent_until = ?, updated_at = ?
            WHERE id = ? AND workspace_id = ? AND consent_revision = ?
            """, next, until, now, id, current.workspace(), revision);
        audit(id, authorize ? "authorized" : "withdrawn", null, now);
        return new Consent(id, next, authorize ? ALLOWED : current.scenario(),
            until == null ? null : Instant.ofEpochMilli(until));
    }

    /**
     * Enrolls two distinct active identities through an explicitly assigned administrative scope.
     *
     * @param operator current ContiNew session and tenant
     * @param workspace exact configured synthetic workspace
     * @param participant provisioned synthetic participant account
     * @param guardian proposed synthetic guardian account
     * @param scenario scenario of the local eligibility test
     * @param confirm affirmative enrollment decision
     * @return bounded enrollment and current consent generation
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Consent enroll(Operator operator, String workspace, String participant, String guardian,
        String scenario, boolean confirm) {
        requirePilot(workspace);
        if (!enabled) {
            throw unavailable();
        }
        if (!confirm || !uuid(participant) || !uuid(guardian) || participant.equals(guardian)
            || !(GUARDIAN_REQUIRED.equals(scenario) || OUTSIDE_REGION.equals(scenario))) {
            throw invalid();
        }
        requireOperator(operator, workspace);
        lockParties(participant, guardian);
        if (jdbc.queryForList("""
            SELECT id FROM assistant_workspace WHERE id = ? FOR UPDATE
            """, String.class, workspace).isEmpty()) {
            throw missing();
        }
        String subjectMembership =
            membership(participant, workspace).orElseThrow(AssistantEligibility::missing);
        String guardianMembership =
            membership(guardian, workspace).orElseThrow(AssistantEligibility::missing);
        var existing = participant(participant, workspace, true);
        if (existing.isPresent()) {
            Enrollment prior = existing.orElseThrow();
            if (prior.guardian().equals(guardian) && prior.scenario().equals(scenario)
                && prior.participantMembership().equals(subjectMembership)
                && prior.guardianMembership().equals(guardianMembership)) {
                return view(prior);
            }
            throw conflict("This participant already has an active enrollment.");
        }
        if (participant(guardian, workspace, false).isPresent()
            || !guardianRows(new User(participant, null, workspace, null)).isEmpty()) {
            throw conflict(
                "Guardian and participant roles require independent fixture identities.");
        }
        if (guardianRows(new User(guardian, null, workspace, null)).size() >= MAX_GUARDIAN_LINKS
            || jdbc.queryForObject(
                "SELECT COUNT(*) FROM assistant_eligibility_enrollment WHERE workspace_id = ?",
                Integer.class, workspace) >= MAX_ENROLLMENTS) {
            throw conflict("The synthetic enrollment capacity has been reached.");
        }

        String id = UUID.randomUUID().toString();
        String revision = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        jdbc.update(
            """
                INSERT INTO assistant_eligibility_enrollment
                    (id, workspace_id, participant_account_id, guardian_account_id, participant_revision,
                     guardian_revision, scenario, consent_revision, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
            id, workspace, participant, guardian, subjectMembership, guardianMembership, scenario,
            revision, now, now);
        audit(id, "provisioned", operator.id(), now);
        return new Consent(id, revision, scenario, null);
    }

    /**
     * Resolves the currently effective admission epoch for the real sandbox connector flow.
     *
     * @param account exact participant account
     * @param workspace authenticated workspace scope
     * @param lock whether to serialize the effect with guardian changes in this transaction
     * @return eligible receipt or an appropriate scoped denial status
     */
    public Admission connector(String account, String workspace, boolean lock) {
        if (!pilot(workspace)) {
            return UNRESTRICTED;
        }
        if (!journal.acceptsErasure()) {
            return HELD;
        }
        return participant(account, workspace, lock).filter(this::valid)
            .map(this::effective).orElse(HELD);
    }

    /**
     * Creates a stable admission error used by existing protected connectors.
     *
     * @return typed current-consent refusal
     */
    public static AssistantApiException denied() {
        return new AssistantApiException(HttpStatus.FORBIDDEN, "ELIGIBILITY_REQUIRED",
            "The current synthetic eligibility and consent do not allow this connector operation.");
    }

    private boolean pilot(String workspace) {
        return !fixtureWorkspace.isBlank() && fixtureWorkspace.equals(workspace);
    }

    private void requirePilot(String workspace) {
        if (!pilot(workspace) || !journal.acceptsErasure()) {
            throw unavailable();
        }
    }

    private void requireOperator(Operator operator, String workspace) {
        var grants = jdbc.queryForList("""
            SELECT can_decide FROM assistant_moderation_grant
            WHERE workspace_id = ? AND admin_tenant_id = ? AND admin_user_id = ? FOR UPDATE
            """, Boolean.class, workspace, operator.tenantId(), operator.id());
        if (grants.isEmpty() || !Boolean.TRUE.equals(grants.getFirst())) {
            throw forbidden();
        }
    }

    private void lockParties(String first, String second) {
        for (String account : List.of(first, second).stream().sorted().toList()) {
            accounts.lockActive(account);
        }
    }

    private void requireSession(Principal actor) {
        if (jdbc
            .queryForList(
                """
                    SELECT id FROM assistant_session WHERE id = ? AND account_id = ? AND expires_at > ? FOR UPDATE
                    """,
                String.class, actor.sessionId(), actor.user().id(), System.currentTimeMillis())
            .isEmpty()) {
            throw new AssistantApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED",
                "A current consumer session is required.");
        }
    }

    private Optional<Enrollment> participant(String account, String workspace, boolean lock) {
        return jdbc.query(ENROLLMENTS + " AND participant_account_id = ?"
            + (lock ? " FOR UPDATE" : ""), ENROLLMENT_ROW, workspace, account).stream().findFirst();
    }

    private List<Enrollment> guardianRows(User guardian) {
        return jdbc.query(ENROLLMENTS + " AND guardian_account_id = ? ORDER BY id LIMIT 16",
            ENROLLMENT_ROW, guardian.workspaceId(), guardian.id());
    }

    private Optional<Enrollment> guardianRow(User guardian, String id, boolean lock) {
        return jdbc.query(ENROLLMENTS + " AND guardian_account_id = ? AND id = ?"
            + (lock ? " FOR UPDATE" : ""), ENROLLMENT_ROW, guardian.workspaceId(), guardian.id(),
            id).stream().findFirst();
    }

    private Optional<String> membership(String account, String workspace) {
        if (journal.blocksCollaborationParticipant(account)) {
            return Optional.empty();
        }
        return jdbc.queryForList("""
            SELECT m.membership_revision FROM assistant_workspace_member m
            JOIN assistant_account a ON a.id = m.account_id AND a.deleted_at IS NULL
            WHERE m.account_id = ? AND m.workspace_id = ?
            """, String.class, account, workspace).stream().findFirst()
            .filter(value -> !journal.blocksWorkspaceMembership(account, workspace, value));
    }

    private boolean valid(Enrollment row) {
        return membership(row.participant(), row.workspace())
            .filter(row.participantMembership()::equals).isPresent()
            && membership(row.guardian(), row.workspace())
                .filter(row.guardianMembership()::equals).isPresent();
    }

    private Admission effective(Enrollment row) {
        if (OUTSIDE_REGION.equals(row.scenario())) {
            return new Admission(null, OUTSIDE_REGION);
        }
        if (row.until() == null
            || journal.guardianConsentRetired(row.participant(), row.workspace(), row.revision())) {
            return new Admission(null, GUARDIAN_REQUIRED);
        }
        return row.until() <= System.currentTimeMillis() ? new Admission(null, EXPIRED)
            : new Admission(row.revision(), ALLOWED);
    }

    private Consent view(Enrollment row) {
        Admission current = effective(row);
        return new Consent(row.id(), row.revision(), current.connectorStatus(),
            row.until() == null || GUARDIAN_REQUIRED.equals(current.connectorStatus())
                ? null : Instant.ofEpochMilli(row.until()));
    }

    private void audit(String id, String action, Long operator, long now) {
        jdbc.update(
            """
                INSERT INTO assistant_eligibility_audit(id, enrollment_id, action, platform_operator_id, created_at)
                VALUES (?, ?, ?, ?, ?)
                """,
            UUID.randomUUID().toString(), id, action, operator, now);
    }

    private static boolean uuid(String value) {
        try {
            return value != null && value.length() == 36
                && UUID.fromString(value).toString().equals(value);
        } catch (IllegalArgumentException failure) {
            return false;
        }
    }

    private static AssistantApiException invalid() {
        return new AssistantApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
            "Confirm a valid scoped synthetic eligibility decision.");
    }

    private static AssistantApiException missing() {
        return new AssistantApiException(HttpStatus.NOT_FOUND, "NOT_FOUND",
            "The current eligibility record is unavailable.");
    }

    private static AssistantApiException forbidden() {
        return new AssistantApiException(HttpStatus.FORBIDDEN, "FORBIDDEN",
            "This synthetic eligibility command is unavailable for the current role.");
    }

    private static AssistantApiException unavailable() {
        return new AssistantApiException(HttpStatus.FORBIDDEN, "CAPABILITY_UNAVAILABLE",
            "Synthetic eligibility enrollment is unavailable.");
    }

    private static AssistantApiException conflict(String detail) {
        return new AssistantApiException(HttpStatus.CONFLICT, "ELIGIBILITY_CHANGED", detail);
    }

    private record Enrollment(String id, String workspace, String participant, String guardian,
        String participantMembership, String guardianMembership, String scenario, String revision,
        Long until) {
    }

    /**
     * Application entry status without other user identities.
     *
     * @param applicable whether this workspace is the fixture scope
     * @param connectorStatus reasoned connector consent decision
     */
    public record Status(boolean applicable, String connectorStatus) {
    }

    /**
     * Internal effect admission and exact consent generation.
     *
     * @param revision approved consent generation, null outside the pilot
     * @param connectorStatus reasoned consent decision
     */
    public record Admission(String revision, String connectorStatus) {

        /**
         * Reports whether the current operation can proceed.
         *
         * @return true when currently allowed
         */
        public boolean allowed() {
            return ALLOWED.equals(connectorStatus);
        }

        /**
         * Checks the exact still-effective authorization of a previous approval.
         *
         * @param admitted original recorded consent generation
         * @return true when the exact consent remains current
         */
        public boolean matches(String admitted) {
            return allowed() && Objects.equals(revision, admitted);
        }
    }

    /**
     * Bounded guardian consent receipt with no recipient data or other user identity.
     *
     * @param id opaque enrollment ID
     * @param revision exact reviewed consent generation
     * @param status effective native eligibility status
     * @param expiresAt actual consent expiry if applicable
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Consent(String id, String revision, String status, Instant expiresAt) {
    }

    /**
     * Current guardian's own bounded receipt collection.
     *
     * @param items verified guardian consent records
     */
    public record Consents(List<Consent> items) {
    }

    /**
     * Independently authenticated platform administration actor.
     *
     * @param id administrative login ID
     * @param tenantId exact current admin tenant
     */
    public record Operator(long id, long tenantId) {
    }
}

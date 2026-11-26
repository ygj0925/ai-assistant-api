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

package top.continew.admin.assistant.connector;

import com.fasterxml.jackson.annotation.JsonInclude;
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
import top.continew.admin.assistant.account.AssistantAccounts;
import top.continew.admin.assistant.account.AssistantAccounts.Principal;
import top.continew.admin.assistant.account.AssistantAccounts.User;
import top.continew.admin.assistant.account.AssistantApiException;
import top.continew.admin.assistant.eligibility.AssistantEligibility;
import top.continew.admin.assistant.eligibility.AssistantEligibility.Admission;
import top.continew.admin.assistant.organization.AssistantOrganizationPolicies;
import top.continew.admin.assistant.organization.AssistantOrganizationPolicies.Enforcement;
import top.continew.admin.assistant.privacy.AssistantDeletionJournal;
import top.continew.admin.assistant.privacy.AssistantErasure;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Authorizes and reconciles one local synthetic effect per approved operation.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
@Service
@ConditionalOnProperty(name = "assistant.enabled", havingValue = "true")
public class AssistantSandboxConnector {

    static final String KIND = "fixture_notice";
    private static final String AWAITING = "awaiting_approval";
    private static final String APPROVED = "approved";
    private static final String PENDING = "pending_result";
    private static final String COMPLETED = "completed";
    private static final String BLOCKED = "blocked";
    private static final String CANCELLED = "cancelled";
    private static final int CAPACITY = 64;
    private static final int OPEN_CAPACITY = 8;
    private static final int GRANT_CAPACITY = 16;
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9._~-]{1,128}");
    private static final String GRANT =
        """
            SELECT id, authorized_at, revoked_at, membership_revision, ledger_id FROM assistant_sandbox_connector_grant
            WHERE account_id = ? AND workspace_id = ?
            """;
    private static final String ACTION = """
        SELECT a.id, a.authorization_id, a.approval_revision, a.simulation, a.state,
            a.organization_policy_revision, a.eligibility_revision,
            a.created_at, a.updated_at, a.recorded_at, g.revoked_at, g.ledger_id
        FROM assistant_sandbox_connector_action a
        JOIN assistant_sandbox_connector_grant g ON a.authorization_id = g.id
            AND a.account_id = g.account_id AND a.workspace_id = g.workspace_id
        WHERE a.account_id = ? AND a.workspace_id = ?
        """;
    private static final String SAME_MEMBERSHIP = """
        AND g.membership_revision = ?
        """;
    private static final RowMapper<GrantRow> GRANT_ROW = (row, index) -> new GrantRow(
        row.getString("id"), row.getLong("authorized_at"), row.getObject("revoked_at", Long.class),
        row.getString("membership_revision"), row.getString("ledger_id"));
    private static final RowMapper<ActionRow> ACTION_ROW = (row, index) -> new ActionRow(
        row.getString("id"), row.getString("authorization_id"), row.getString("approval_revision"),
        row.getString("simulation"), row.getString("state"),
        row.getLong("created_at"), row.getLong("updated_at"),
        row.getObject("recorded_at", Long.class),
        row.getObject("revoked_at", Long.class), row.getString("ledger_id"),
        row.getString("organization_policy_revision"), row.getString("eligibility_revision"));
    private static final Authorization DISCONNECTED = new Authorization("disconnected", null, null);

    private final JdbcTemplate jdbc;
    private final AssistantAccounts accounts;
    private final AssistantDeletionJournal journal;
    private final AssistantErasure erasure;
    private final SandboxConnectorLedger ledger;
    private final AssistantOrganizationPolicies organizationPolicies;
    private final AssistantEligibility eligibility;
    private final TransactionTemplate transactions;

    /**
     * Wires consumer authorization, same-scope transactional storage and the isolated ledger.
     *
     * @param jdbc assistant database
     * @param accounts consumer account lifecycle
     * @param journal independent revocation and dispatch protections
     * @param erasure durable event application
     * @param ledger example effect and receipt verification
     * @param organizationPolicies current workspace connector policy and approval versions
     * @param eligibility current scoped guardian authorization and consent epochs
     * @param manager transaction manager
     */
    public AssistantSandboxConnector(JdbcTemplate jdbc, AssistantAccounts accounts,
        AssistantDeletionJournal journal, AssistantErasure erasure, SandboxConnectorLedger ledger,
        AssistantOrganizationPolicies organizationPolicies, AssistantEligibility eligibility,
        PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.journal = journal;
        this.erasure = erasure;
        this.ledger = ledger;
        this.organizationPolicies = organizationPolicies;
        this.eligibility = eligibility;
        transactions = new TransactionTemplate(manager);
        transactions.setIsolationLevel(TransactionTemplate.ISOLATION_READ_COMMITTED);
    }

    /**
     * Reports whether the isolated simulator can currently accept approved operations.
     *
     * @param user current workspace account
     * @return whether the capability can be advertised
     */
    public boolean available(User user) {
        return user != null && ready() && member(user.id(), user.workspaceId(), false).isPresent()
            && organizationPolicies.effective(user.workspaceId(), false).sandboxConnectorAllowed()
            && eligibility.connector(user.id(), user.workspaceId(), false).allowed();
    }

    /**
     * Reports whether a current member can validate local simulator receipts.
     *
     * @param user selected workspace member
     * @return whether the owned source is usable
     */
    public boolean eventSourceAvailable(User user) {
        return user != null && ledger.canRead()
            && member(user.id(), user.workspaceId(), false).isPresent();
    }

    /**
     * Reads or locks the consumer's exact current membership generation.
     *
     * @param actor authenticated consumer
     * @param lock whether to acquire the mutation session and scope locks
     * @return the current membership generation
     */
    public String eventMembership(Principal actor, boolean lock) {
        return scope(actor, lock).membership();
    }

    /**
     * Locks the current member and checks the exact reviewed simulator grant.
     *
     * @param actor authenticated consumer
     * @param authorizationId immutable grant identifier
     * @return selected membership generation
     */
    public String lockEventAuthorization(Principal actor, String authorizationId) {
        return eventScope(actor, authorizationId).membership();
    }

    /**
     * Checks the exact action and current connector policy before an in-app delivery.
     *
     * @param owner currently selected account and workspace
     * @param authorizationId immutable grant identifier
     * @param operationId previously validated operation identifier
     * @return whether the source can still trigger a new delivery
     */
    public boolean hasEventAuthorization(User owner, String authorizationId, String operationId) {
        if (!ledger.canRead()) {
            return false;
        }
        var scope = member(owner.id(), owner.workspaceId(), false)
            .filter(value -> activeGrant(value).filter(grant -> grant.id().equals(authorizationId))
                .isPresent());
        if (scope.isEmpty()) {
            return false;
        }
        var selected = scope.orElseThrow();
        Enforcement policy = organizationPolicies.effective(selected.workspace(), true);
        Admission consent = consent(selected, true);
        return jdbc.query(ACTION + SAME_MEMBERSHIP + "AND a.id = ?", ACTION_ROW, selected.account(),
            selected.workspace(), selected.membership(), operationId).stream().findFirst()
            .filter(
                row -> row.authorization().equals(authorizationId) && COMPLETED.equals(row.state())
                    && row.recordedAt() != null && permits(policy, consent, row))
            .isPresent();
    }

    /**
     * Validates an event against an exact completed action and the independent simulator receipt.
     *
     * @param actor authenticated consumer
     * @param authorizationId reviewed current grant
     * @param operationId exact completed simulator operation
     * @return time and membership of the verified event
     */
    public VerifiedEvent verifyEventReceipt(Principal actor, String authorizationId,
        String operationId) {
        requireUuid(operationId);
        Scope scope = eventScope(actor, authorizationId);
        ActionRow row = owned(scope, operationId, true);
        if (!row.authorization().equals(authorizationId) || !COMPLETED.equals(row.state())
            || row.recordedAt() == null
            || !permits(organizationPolicies.effective(scope.workspace(), true),
                consent(scope, true), row)) {
            throw missing();
        }
        Instant recorded = ledger.find(operationId)
            .filter(receipt -> receipt.toEpochMilli() == row.recordedAt())
            .orElseThrow(AssistantSandboxConnector::missing);
        return new VerifiedEvent(scope.membership(), recorded);
    }

    private Scope eventScope(Principal actor, String authorizationId) {
        requireUuid(authorizationId);
        if (!ledger.canRead()) {
            throw unavailable();
        }
        Scope scope = scope(actor, true);
        connectorPolicy(scope.workspace());
        requireConsent(scope);
        if (activeGrant(scope).filter(grant -> grant.id().equals(authorizationId)).isEmpty()) {
            throw authorizationChanged();
        }
        return scope;
    }

    Authorization authorization(Principal actor) {
        Scope scope = scope(actor, false);
        var authorization = activeGrant(scope).map(GrantRow::authorization).orElse(DISCONNECTED);
        verifyScope(scope);
        return authorization;
    }

    /**
     * Creates or returns the current local simulator authorization.
     *
     * @param actor authenticated consumer
     * @return active authorization
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Authorization authorize(Principal actor) {
        requireReady();
        Scope scope = scope(actor, true);
        connectorPolicy(scope.workspace());
        requireConsent(scope);
        var current = activeGrant(scope);
        if (current.isPresent()) {
            return current.orElseThrow().authorization();
        }
        int count = jdbc.queryForObject("""
            SELECT COUNT(*) FROM assistant_sandbox_connector_grant WHERE account_id = ?
            """, Integer.class, scope.account());
        if (count >= GRANT_CAPACITY) {
            throw capacity();
        }
        String id = UUID.randomUUID().toString();
        long at = System.currentTimeMillis();
        jdbc.update("""
            INSERT INTO assistant_sandbox_connector_grant
                (id, account_id, workspace_id, membership_revision, ledger_id, authorized_at)
            VALUES (?, ?, ?, ?, ?, ?)
            """, id, scope.account(), scope.workspace(), scope.membership(), ledger.binding(), at);
        return new Authorization("authorized", id, Instant.ofEpochMilli(at));
    }

    /**
     * Revokes a known local authorization generation.
     *
     * @param actor authenticated consumer
     * @param id authorization identifier
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void revoke(Principal actor, String id) {
        requireUuid(id);
        Scope scope = scope(actor, true);
        GrantRow grant = jdbc.query(GRANT + "AND id = ? FOR UPDATE", GRANT_ROW,
            scope.account(), scope.workspace(), id).stream()
            .filter(row -> Objects.equals(row.membership(), scope.membership())).findFirst()
            .orElseThrow(AssistantSandboxConnector::missing);
        if (grant.revokedAt() == null && !authorizationRevoked(scope, id)) {
            erasure.revokeSandboxConnector(actor.user(), id);
        }
    }

    /**
     * Stores the exact synthetic request for later approval.
     *
     * @param actor authenticated consumer
     * @param key caller retry key
     * @param authorization reviewed authorization identifier
     * @param simulation selected acknowledgement scenario
     * @return awaiting action
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Action create(Principal actor, String key, String authorization, String simulation) {
        requireReady();
        requireUuid(authorization);
        if (key == null || !KEY.matcher(key).matches()
            || !("acknowledged".equals(simulation) || "ack_lost".equals(simulation))) {
            throw invalid("A stable key and a supported simulator outcome are required.");
        }
        Scope scope = scope(actor, true);
        String digest = digest(scope.account() + "\n" + scope.workspace() + "\n"
            + Objects.toString(scope.membership(), "") + "\n" + key);
        var previous = jdbc.query(ACTION + SAME_MEMBERSHIP + "AND a.idempotency_digest = ?",
            ACTION_ROW, scope.account(), scope.workspace(), scope.membership(),
            digest).stream().findFirst();
        if (previous.isPresent()) {
            ActionRow row = previous.orElseThrow();
            if (!row.authorization().equals(authorization)
                || !row.simulation().equals(simulation)) {
                throw conflict("CONFLICT", "The operation key belongs to a different request.");
            }
            return visible(scope, row);
        }
        Enforcement policy = connectorPolicy(scope.workspace());
        Admission consent = requireConsent(scope);
        if (activeGrant(scope).filter(row -> row.id().equals(authorization)).isEmpty()) {
            throw authorizationChanged();
        }
        var count = jdbc.queryForObject(
            """
                SELECT COUNT(*) AS total,
                    COUNT(CASE WHEN a.state = 'pending_result' OR (
                        a.state IN ('awaiting_approval','approved') AND (
                            (p.workspace_id IS NULL AND a.organization_policy_revision IS NULL)
                            OR p.revision = a.organization_policy_revision)
                        AND (a.eligibility_revision IS NULL OR (
                            e.consent_revision = a.eligibility_revision AND e.consent_until > ?)))
                        THEN 1 END) AS pending
                FROM assistant_sandbox_connector_action a LEFT JOIN assistant_organization_policy p
                    ON p.workspace_id = a.workspace_id
                LEFT JOIN assistant_eligibility_enrollment e
                    ON e.workspace_id = a.workspace_id AND e.participant_account_id = a.account_id
                WHERE a.account_id = ?
                """,
            (row, index) -> new Count(row.getInt("total"), row.getInt("pending")),
            System.currentTimeMillis(), scope.account());
        if (count == null || count.total() >= CAPACITY || count.pending() >= OPEN_CAPACITY) {
            throw capacity();
        }
        String id = UUID.nameUUIDFromBytes(("assistant.connector.action.v1:" + digest)
            .getBytes(StandardCharsets.US_ASCII)).toString();
        if (journal.sandboxActionStarted(scope.account(), scope.workspace(), id)
            || journal.sandboxActionCancelled(scope.account(), scope.workspace(), id)
            || ledger.find(id).isPresent()) {
            throw conflict("OPERATION_RESTORED",
                "This operation key was already used before recovery.");
        }
        String revision = UUID.randomUUID().toString();
        long at = System.currentTimeMillis();
        jdbc.update(
            """
                INSERT INTO assistant_sandbox_connector_action
                    (id, account_id, workspace_id, authorization_id, approval_revision, idempotency_digest,
                     simulation, state, created_at, updated_at, organization_policy_revision,
                     eligibility_revision)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'awaiting_approval', ?, ?, ?, ?)
                """,
            id, scope.account(), scope.workspace(), authorization, revision, digest, simulation, at,
            at, policy.revision(), consent.revision());
        return new Action(id, authorization, KIND, simulation, revision, AWAITING,
            Instant.ofEpochMilli(at), Instant.ofEpochMilli(at), null);
    }

    Action get(Principal actor, String id) {
        requireUuid(id);
        Scope scope = scope(actor, false);
        var action = visible(scope, owned(scope, id, false));
        verifyScope(scope);
        return action;
    }

    Page list(Principal actor, Integer limit, String cursor) {
        Scope scope = scope(actor, false);
        int size = limit == null ? 25 : limit;
        if (size < 1 || size > 50 || cursor != null && !SandboxConnectorLedger.uuid(cursor)) {
            throw invalid("A valid action cursor and a limit between 1 and 50 are required.");
        }
        var rows = jdbc.query(ACTION + SAME_MEMBERSHIP + "AND a.id < ? ORDER BY a.id DESC LIMIT ?",
            ACTION_ROW, scope.account(), scope.workspace(), scope.membership(),
            cursor == null ? "z" : cursor, size + 1);
        Enforcement policy = organizationPolicies.effective(scope.workspace(), false);
        Admission consent = consent(scope, false);
        List<Action> items =
            rows.stream().limit(size).map(row -> visible(scope, row, policy, consent)).toList();
        verifyScope(scope);
        return new Page(items, rows.size() <= size ? null : items.get(size - 1).id());
    }

    /**
     * Approves only the reviewed action and matching authorization.
     *
     * @param actor authenticated consumer
     * @param id action identifier
     * @param revision reviewed revision
     * @return current action
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Action approve(Principal actor, String id, String revision) {
        requireReady();
        requireUuid(id);
        requireUuid(revision);
        Scope scope = scope(actor, true);
        ActionRow row = owned(scope, id, true);
        if (!row.revision().equals(revision)) {
            throw conflict("APPROVAL_CHANGED", "The reviewed action has changed.");
        }
        if (AWAITING.equals(row.state()) || APPROVED.equals(row.state())) {
            if (!connectorPolicy(scope.workspace()).matches(row.organizationPolicyRevision())) {
                throw AssistantOrganizationPolicies.denied();
            }
            if (!requireConsent(scope).matches(row.eligibilityRevision())) {
                throw AssistantEligibility.denied();
            }
        }
        Action action = visible(scope, row);
        if (BLOCKED.equals(action.state())) {
            throw authorizationChanged();
        }
        if (CANCELLED.equals(action.state())) {
            throw conflict("CONFLICT", "The action is already cancelled.");
        }
        if (AWAITING.equals(action.state())) {
            state(id, APPROVED);
        }
        return visible(scope, owned(scope, id, false));
    }

    /**
     * Withdraws an action before irreversible dispatch.
     *
     * @param actor authenticated consumer
     * @param id action identifier
     * @return current action
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Action cancel(Principal actor, String id) {
        requireUuid(id);
        Scope scope = scope(actor, true);
        ActionRow row = owned(scope, id, true);
        var action = visible(scope, row);
        if (PENDING.equals(action.state()) || COMPLETED.equals(action.state())) {
            throw conflict("ACTION_IN_DOUBT", "The action has already been dispatched.");
        }
        if ((AWAITING.equals(row.state()) || APPROVED.equals(row.state()))
            && !CANCELLED.equals(action.state())) {
            erasure.cancelSandboxConnectorAction(actor.user(), id);
        }
        return visible(scope, owned(scope, id, false));
    }

    /**
     * Queries the original local simulator receipt.
     *
     * @param actor authenticated consumer
     * @param id action identifier
     * @return current action with any verified outcome
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Action reconcile(Principal actor, String id) {
        if (!journal.acceptsErasure() || !ledger.canRead()) {
            throw unavailable();
        }
        requireUuid(id);
        Scope scope = scope(actor, true);
        ActionRow row = owned(scope, id, true);
        if (PENDING.equals(visible(scope, row).state())
            && Objects.equals(ledger.binding(), row.ledgerId())) {
            ledger.find(id).ifPresent(at -> complete(id, at));
        }
        return visible(scope, owned(scope, id, false));
    }

    /**
     * Claims bounded pre-approved work without holding a general scheduler lock.
     */
    @Scheduled(fixedDelay = 500)
    public void tick() {
        if (!ready() || journal.dispatchPaused()) {
            return;
        }
        var candidates = jdbc.query(
            """
                SELECT a.id, a.account_id, a.workspace_id
                FROM assistant_sandbox_connector_action a
                JOIN assistant_sandbox_connector_grant g ON g.id = a.authorization_id
                JOIN assistant_account u ON u.id = a.account_id
                JOIN assistant_workspace_member m ON m.account_id = a.account_id AND m.workspace_id = a.workspace_id
                WHERE a.state = 'approved' AND g.revoked_at IS NULL AND g.ledger_id = ? AND u.deleted_at IS NULL
                AND g.membership_revision = m.membership_revision
                ORDER BY a.created_at, a.id LIMIT 4
                """,
            (row, index) -> new Candidate(row.getString("id"), row.getString("account_id"),
                row.getString("workspace_id")),
            ledger.binding());
        for (Candidate candidate : candidates) {
            transactions.executeWithoutResult(ignored -> dispatch(candidate));
        }
    }

    private void dispatch(Candidate candidate) {
        if (!ready() || journal.dispatchPaused()
            || jdbc.queryForList(
                """
                    SELECT id FROM assistant_account WHERE id = ? AND deleted_at IS NULL FOR UPDATE SKIP LOCKED
                    """,
                String.class, candidate.account()).isEmpty()) {
            return;
        }
        var authorizedScope = member(candidate.account(), candidate.workspace(), true);
        if (authorizedScope.isEmpty()) {
            return;
        }
        Scope scope = authorizedScope.orElseThrow();
        var stored = jdbc.query(ACTION + SAME_MEMBERSHIP + "AND a.id = ? FOR UPDATE", ACTION_ROW,
            scope.account(), scope.workspace(), scope.membership(),
            candidate.id()).stream().findFirst();
        if (stored.isEmpty()) {
            return;
        }
        ActionRow row = stored.orElseThrow();
        String state = visible(scope, row).state();
        if (BLOCKED.equals(state) && APPROVED.equals(row.state())) {
            state(row.id(), BLOCKED);
            return;
        }
        if (!APPROVED.equals(state) || !ready()) {
            return;
        }
        Enforcement policy = organizationPolicies.effective(scope.workspace(), true);
        if (!permits(policy, consent(scope, true), row)) {
            state(row.id(), BLOCKED);
            return;
        }
        User actor = new User(scope.account(), null, scope.workspace(), null);
        erasure.startSandboxConnectorAction(actor, row.id());
        acceptEffect(row);
    }

    private void acceptEffect(ActionRow row) {
        Instant recorded;
        try {
            recorded = ledger.record(row.id(), Instant.now());
        } catch (IllegalStateException failure) {
            // Durable start survives an unconfirmed effect. A later check only reads the independent ledger.
            return;
        }
        if ("acknowledged".equals(row.simulation())) {
            complete(row.id(), recorded);
        }
    }

    private void complete(String id, Instant recorded) {
        jdbc.update("""
            UPDATE assistant_sandbox_connector_action
            SET state = 'completed', recorded_at = ?, updated_at = ?
            WHERE id = ? AND state = 'pending_result'
            """, recorded.toEpochMilli(), System.currentTimeMillis(), id);
    }

    private void state(String id, String state) {
        jdbc.update(
            "UPDATE assistant_sandbox_connector_action SET state = ?, updated_at = ? WHERE id = ?",
            state, System.currentTimeMillis(), id);
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
            .orElseThrow(AssistantSandboxConnector::missing);
    }

    private void verifyScope(Scope scope) {
        if (member(scope.account(), scope.workspace(), false).filter(scope::equals).isEmpty()) {
            throw missing();
        }
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
                (row, index) -> new Scope(account, workspace, row.getString("membership_revision")),
                account, workspace)
            .stream().filter(scope -> !journal.blocksWorkspaceMembership(account, workspace,
                scope.membership()))
            .findFirst();
    }

    private Optional<GrantRow> activeGrant(Scope scope) {
        return jdbc
            .query(GRANT + "AND revoked_at IS NULL ORDER BY authorized_at DESC, id DESC LIMIT 16",
                GRANT_ROW, scope.account(), scope.workspace())
            .stream()
            .filter(grant -> Objects.equals(grant.membership(), scope.membership())
                && !authorizationRevoked(scope, grant.id())
                && (ledger.binding() == null || Objects.equals(ledger.binding(), grant.ledgerId())))
            .findFirst();
    }

    private boolean authorizationRevoked(Scope scope, String id) {
        return journal.sandboxAuthorizationRevoked(scope.account(), scope.workspace(), id);
    }

    private ActionRow owned(Scope scope, String id, boolean lock) {
        return jdbc.query(ACTION + SAME_MEMBERSHIP + "AND a.id = ?" + (lock ? " FOR UPDATE" : ""),
            ACTION_ROW, scope.account(), scope.workspace(), scope.membership(),
            id).stream().findFirst().orElseThrow(AssistantSandboxConnector::missing);
    }

    private Action visible(Scope scope, ActionRow row) {
        return visible(scope, row, organizationPolicies.effective(scope.workspace(), false),
            consent(scope, false));
    }

    private Action visible(Scope scope, ActionRow row, Enforcement policy, Admission consent) {
        String state = row.state();
        if (AWAITING.equals(state) || APPROVED.equals(state)) {
            if (journal.sandboxActionStarted(scope.account(), scope.workspace(), row.id())) {
                state = PENDING;
            } else if (journal.sandboxActionCancelled(scope.account(), scope.workspace(),
                row.id())) {
                state = CANCELLED;
            } else if (row.revokedAt() != null || authorizationRevoked(scope, row.authorization())
                || ledger.binding() != null && !Objects.equals(ledger.binding(), row.ledgerId())
                || !permits(policy, consent, row)) {
                state = BLOCKED;
            }
        }
        Result result = row.recordedAt() == null ? null
            : new Result("fixture_receipt", Instant.ofEpochMilli(row.recordedAt()));
        return new Action(row.id(), row.authorization(), KIND, row.simulation(), row.revision(),
            state, Instant.ofEpochMilli(row.createdAt()), Instant.ofEpochMilli(row.updatedAt()),
            result);
    }

    private boolean ready() {
        return journal.acceptsErasure() && ledger.canRecord();
    }

    private Enforcement connectorPolicy(String workspace) {
        Enforcement current = organizationPolicies.effective(workspace, true);
        if (!current.sandboxConnectorAllowed()) {
            throw AssistantOrganizationPolicies.denied();
        }
        return current;
    }

    private Admission consent(Scope scope, boolean lock) {
        return eligibility.connector(scope.account(), scope.workspace(), lock);
    }

    private Admission requireConsent(Scope scope) {
        Admission current = consent(scope, true);
        if (!current.allowed()) {
            throw AssistantEligibility.denied();
        }
        return current;
    }

    private static boolean permits(Enforcement current, Admission consent, ActionRow row) {
        return current.sandboxConnectorAllowed()
            && current.matches(row.organizationPolicyRevision())
            && consent.matches(row.eligibilityRevision());
    }

    private void requireReady() {
        if (!ready()) {
            throw unavailable();
        }
    }

    private static void requireUuid(String id) {
        if (!SandboxConnectorLedger.uuid(id)) {
            throw invalid("A valid action, authorization or review identifier is required.");
        }
    }

    private static AssistantApiException unavailable() {
        return new AssistantApiException(HttpStatus.FORBIDDEN, "CAPABILITY_UNAVAILABLE",
            "The local connector simulator is unavailable.");
    }

    private static AssistantApiException invalid(String detail) {
        return new AssistantApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", detail);
    }

    private static AssistantApiException missing() {
        return new AssistantApiException(HttpStatus.NOT_FOUND, "NOT_FOUND",
            "The local connector operation was not found.");
    }

    private static AssistantApiException conflict(String code, String detail) {
        return new AssistantApiException(HttpStatus.CONFLICT, code, detail);
    }

    private static AssistantApiException authorizationChanged() {
        return conflict("CONNECTOR_AUTHORIZATION_CHANGED", "The connection authorization changed.");
    }

    private static AssistantApiException capacity() {
        return new AssistantApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED",
            "The local connector operation limit has been reached.");
    }

    private static String digest(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable.", error);
        }
    }

    /**
     * Current consumer authorization.
     *
     * @param state authorized or disconnected
     * @param authorizationId immutable currently active grant
     * @param authorizedAt original confirmation time
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Authorization(String state, String authorizationId, Instant authorizedAt) {
    }

    /**
     * One immutable approved effect with its current lifecycle state.
     *
     * @param id operation reference
     * @param authorizationId authorization bound at creation
     * @param kind fixed effect type
     * @param simulation reviewed acknowledgement scenario
     * @param approvalRevision exact reviewed revision
     * @param state current resolved state
     * @param createdAt creation time
     * @param updatedAt most recent persisted state time
     * @param result confirmed receipt without payload or provider secrets
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Action(String id, String authorizationId, String kind, String simulation,
        String approvalRevision, String state, Instant createdAt, Instant updatedAt,
        Result result) {
    }

    /**
     * Confirmed synthetic effect receipt.
     *
     * @param kind fixed receipt type
     * @param recordedAt authoritative simulator time
     */
    public record Result(String kind, Instant recordedAt) {
    }

    /**
     * Scoped action list.
     *
     * @param items at most the requested bound
     * @param nextCursor continuation or null
     */
    public record Page(List<Action> items,
        @JsonInclude(JsonInclude.Include.ALWAYS) String nextCursor) {
    }

    /**
     * Scoped event witness backed by a confirmed simulator receipt.
     *
     * @param membershipRevision exact current authorization generation
     * @param recordedAt authoritative receipt time
     */
    public record VerifiedEvent(String membershipRevision, Instant recordedAt) {
    }

    private record Count(int total, int pending) {
    }

    private record Scope(String account, String workspace, String membership) {
    }

    private record GrantRow(String id, long authorizedAt, Long revokedAt, String membership,
        String ledgerId) {

        Authorization authorization() {
            return new Authorization("authorized", id, Instant.ofEpochMilli(authorizedAt));
        }
    }

    private record ActionRow(String id, String authorization, String revision, String simulation,
        String state, long createdAt, long updatedAt, Long recordedAt,
        Long revokedAt, String ledgerId, String organizationPolicyRevision,
        String eligibilityRevision) {
    }

    private record Candidate(String id, String account, String workspace) {
    }
}

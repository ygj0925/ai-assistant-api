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

package top.continew.admin.assistant.organization;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import top.continew.admin.assistant.account.AssistantAccounts;
import top.continew.admin.assistant.account.AssistantAccounts.Principal;
import top.continew.admin.assistant.account.AssistantAccounts.User;
import top.continew.admin.assistant.account.AssistantApiException;
import top.continew.admin.assistant.privacy.AssistantDeletionJournal;
import top.continew.admin.assistant.privacy.AssistantErasure;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Manages the active membership roster of explicitly provisioned workspaces.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
@Service
@ConditionalOnProperty(name = "assistant.enabled", havingValue = "true")
public class AssistantOrganizations {

    private static final int MAX_SEATS = 128;
    private static final int MAX_PAGE = 64;
    private static final long AUDIT_RETENTION = Duration.ofDays(90).toMillis();
    private static final String OWNER = "owner";
    private static final String ADMIN = "admin";
    private static final String MEMBER = "member";
    private static final String ORG_QUERY = """
        SELECT workspace_id, owner_account_id, tenant_id, name, seat_limit, provision_digest
        FROM assistant_organization WHERE workspace_id = ?
        """;
    private static final RowMapper<Organization> ORGANIZATION = (row, index) -> new Organization(
        row.getString("workspace_id"), row.getString("owner_account_id"), row.getLong("tenant_id"),
        row.getString("name"), row.getInt("seat_limit"), row.getString("provision_digest"));
    private static final String MEMBERSHIP =
        """
            SELECT m.account_id, m.membership_role, m.membership_revision, m.organization_authority_revision,
                a.username FROM assistant_workspace_member m JOIN assistant_account a ON a.id = m.account_id
            WHERE m.workspace_id = ? AND a.deleted_at IS NULL
            """;
    private static final RowMapper<Member> MEMBERS = (row, index) -> new Member(
        row.getString("account_id"), row.getString("username"), row.getString("membership_role"),
        row.getString("membership_revision"), row.getString("organization_authority_revision"));
    private static final RowMapper<AuditEntry> AUDIT =
        (row, index) -> new AuditEntry(row.getString("id"),
            row.getString("action"), row.getString("actor_account_id"),
            row.getString("target_account_id"),
            row.getString("previous_role"), row.getString("role"),
            Instant.ofEpochMilli(row.getLong("created_at")), row.getString("directory_id"));

    private final JdbcTemplate jdbc;
    private final AssistantAccounts accounts;
    private final AssistantDeletionJournal journal;
    private final AssistantErasure erasure;
    private final TransactionTemplate transactions;
    private final boolean enabled;
    private final boolean provisioningEnabled;

    /**
     * Configures consumer organization membership and platform-only provisioning.
     *
     * @param jdbc scope and audit store
     * @param accounts current account lifecycle checks
     * @param journal bound deletion and authority retirement
     * @param erasure privacy and membership retirement
     * @param transactionManager atomic organization provisioning
     * @param enabled organization management policy
     * @param provisioningEnabled separate administration route policy
     */
    public AssistantOrganizations(JdbcTemplate jdbc, AssistantAccounts accounts,
        AssistantDeletionJournal journal, AssistantErasure erasure,
        PlatformTransactionManager transactionManager,
        @Value("${assistant.organizations.enabled:false}") boolean enabled,
        @Value("${assistant.organizations.provision-enabled:false}") boolean provisioningEnabled) {
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.journal = journal;
        this.erasure = erasure;
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setIsolationLevel(Isolation.READ_COMMITTED.value());
        this.enabled = enabled;
        this.provisioningEnabled = provisioningEnabled;
    }

    /**
     * Reports whether the consumer deployment can safely manage an organization.
     *
     * @return whether the feature and the independent journal are ready
     */
    public boolean available() {
        return enabled && journal.acceptsErasure();
    }

    /**
     * Advertises organization actions only for an active member in the selected provisioned scope.
     *
     * @param user currently scoped consumer
     * @return whether this consumer is eligible for the organization entry
     */
    public boolean availableIn(User user) {
        return qualified(user, false);
    }

    /**
     * Checks whether the selected organization can show its owner policy editor.
     *
     * @param user scoped current consumer
     * @return whether this consumer is the active designated owner
     */
    public boolean policyOwner(User user) {
        return qualified(user, true);
    }

    /**
     * Requires the immutable organization owner for policy authoring.
     *
     * @param principal current scoped consumer and device
     * @param lock whether to serialize a policy mutation with current organization operations
     */
    public void requirePolicyOwner(Principal principal, boolean lock) {
        Access scope = authorize(principal, true);
        if (!OWNER.equals(scope.actor().role())) {
            throw forbidden();
        }
        if (lock) {
            mutationScope(principal, scope, principal.user().id());
        }
    }

    /**
     * Adds an audit receipt in the same transaction as an owner policy update.
     *
     * @param principal already reviewed current organization owner
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordPolicyChange(Principal principal) {
        requirePolicyOwner(principal, false);
        appendAudit(principal.user().workspaceId(), "policy_changed", principal.user().id(),
            null, null, null, null);
    }

    /**
     * Records the confirmed owner-only model budget change in the organization audit.
     *
     * @param principal current designated organization owner
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordModelBudgetChange(Principal principal) {
        requirePolicyOwner(principal, false);
        appendAudit(principal.user().workspaceId(), "model_budget_changed", principal.user().id(),
            null, null, null, null);
    }

    private boolean qualified(User user, boolean ownerOnly) {
        if (user == null || !available()) {
            return false;
        }
        String workspace = user.workspaceId();
        return jdbc.query(
            """
                SELECT m.membership_revision AS actor_revision, o.owner_account_id,
                    own.membership_revision AS owner_revision, own.organization_authority_revision AS owner_authority
                FROM assistant_organization o
                JOIN assistant_workspace w ON w.id = o.workspace_id AND w.personal_owner_id IS NULL
                JOIN assistant_workspace_member m ON m.workspace_id = o.workspace_id AND m.account_id = ?
                JOIN assistant_workspace_member own ON own.workspace_id = o.workspace_id
                    AND own.account_id = o.owner_account_id AND own.membership_role = 'owner'
                JOIN assistant_account a ON a.id = own.account_id AND a.deleted_at IS NULL
                WHERE o.workspace_id = ?
                """,
            (row, index) -> {
                String owner = row.getString("owner_account_id");
                return (!ownerOnly || owner.equals(user.id()))
                    && accounts.allowsMembership(user.id(), workspace,
                        row.getString("actor_revision"))
                    && accounts.allowsMembership(owner, workspace, row.getString("owner_revision"))
                    && !journal.blocksOrganizationAuthority(owner, workspace,
                        row.getString("owner_authority"));
            }, user.id(), workspace).stream().findFirst().orElse(false);
    }

    /**
     * Creates an organization through an already authorized ContiNew tenant operator.
     *
     * @param actor interactive platform login and scoped tenant
     * @param key canonical idempotency UUID
     * @param ownerId existing owner account identifier
     * @param requestedName organization display name
     * @param seats fixed local seat allocation
     * @param confirm explicit provision decision
     * @return workspace view for its designated owner
     */
    public Summary provision(Operator actor, String key, String ownerId, String requestedName,
        int seats, boolean confirm) {
        requireReady();
        if (!provisioningEnabled) {
            throw unavailable(HttpStatus.FORBIDDEN);
        }
        requireId(key);
        requireId(ownerId);
        if (!confirm || seats < 1 || seats > MAX_SEATS || requestedName == null
            || requestedName.isBlank() || requestedName.length() > 80
            || !StandardCharsets.UTF_8.newEncoder().canEncode(requestedName)
            || requestedName.codePoints().anyMatch(Character::isISOControl)) {
            throw invalid("Confirm a valid organization name, owner and seat allocation.");
        }
        String name = requestedName.strip();
        String id = provisionId(actor.tenantId(), key);
        String fingerprint = HexFormat.of().formatHex(digest(ownerId + "\n" + seats + "\n" + name));
        try {
            return transactions.execute(transaction -> {
                lockAccounts(List.of(ownerId));
                Organization previous = organization(id, false);
                if (previous != null) {
                    return provisionRetry(previous, actor, fingerprint);
                }
                long now = System.currentTimeMillis();
                jdbc.update("INSERT INTO assistant_workspace (id, created_at) VALUES (?, ?)", id,
                    now);
                jdbc.update(
                    """
                        INSERT INTO assistant_organization
                            (workspace_id, owner_account_id, tenant_id, name, seat_limit, provision_digest, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """,
                    id, ownerId, actor.tenantId(), name, seats, fingerprint, now);
                addMember(id, ownerId, OWNER);
                appendAudit(id, "provisioned", null, ownerId, null, OWNER, actor.operatorId());
                return new Summary(id, name, OWNER, seats, 1);
            });
        } catch (DuplicateKeyException duplicate) {
            return provisionRetry(organization(id, false), actor, fingerprint);
        }
    }

    /**
     * Reads the enrolled member's current organization and seat counts.
     *
     * @param principal actual consumer and workspace
     * @return the selected organization's safe summary
     */
    public Summary current(Principal principal) {
        Access access = authorize(principal, false);
        Summary view = summary(access.organization(), access.actor().role());
        recheck(principal, access, false);
        return view;
    }

    /**
     * Lists active roster rows for a current owner or administrator.
     *
     * @param principal actual consumer and workspace
     * @param cursor scoped page anchor
     * @param limit bounded page size
     * @return current authorized member rows
     */
    public Page<Member> members(Principal principal, String cursor, Integer limit) {
        Access access = authorize(principal, true);
        String workspace = access.organization().workspaceId();
        String after = anchor(workspace, "members", cursor);
        if (!after.isEmpty()) {
            requireId(after);
        }
        int count = pageSize(limit);
        List<Member> candidates = activeMembers(workspace);
        recheck(principal, access, true);
        var visible = candidates.stream().filter(row -> row.accountId().compareTo(after) > 0
            && accounts.allowsMembership(row.accountId(), workspace, row.membershipRevision()))
            .map(row -> effectiveRole(workspace, row)).limit(count + 1L).toList();
        var items = visible.subList(0, Math.min(count, visible.size()));
        String next = visible.size() > count
            ? encode(workspace, "members", items.getLast().accountId()) : null;
        return new Page<>(items, next);
    }

    /**
     * Enrolls a specific existing account under the caller's bounded role authority.
     *
     * @param principal actual consumer and workspace
     * @param targetId explicitly chosen active account
     * @param role new non-owner role
     * @param confirm explicit enrollment consent
     * @return current enrollment after a fresh action or a same-role retry
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Member enroll(Principal principal, String targetId, String role, boolean confirm) {
        requireChange(targetId, role, confirm);
        Access before = authorize(principal, true);
        requireTargetRole(before, targetId, role);
        Access access = mutationScope(principal, before, targetId);
        String workspace = access.organization().workspaceId();
        Member previous = member(workspace, targetId);
        if (previous != null) {
            requireTargetRole(access, targetId, previous.role());
            if (role.equals(previous.role())) {
                return previous;
            }
            throw stale();
        }
        if (activeMembers(workspace).size() >= access.organization().seatLimit()) {
            throw new AssistantApiException(HttpStatus.CONFLICT, "ORGANIZATION_SEAT_LIMIT",
                "This organization has no available seats.");
        }
        addMember(workspace, targetId, role);
        appendAudit(workspace, "member_added", access.actor().accountId(), targetId, null, role,
            null);
        return requireMember(workspace, targetId);
    }

    /**
     * Changes one exact existing member's authority while retaining their workspace membership.
     *
     * @param principal current organization owner
     * @param targetId exact member account
     * @param membershipRevision current enrollment generation
     * @param authorityRevision current organization role generation
     * @param role new non-owner role
     * @param confirm explicit role change confirmation
     * @return fresh authority projection
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Member changeRole(Principal principal, String targetId, String membershipRevision,
        String authorityRevision, String role, boolean confirm) {
        requireChange(targetId, role, confirm);
        requireId(membershipRevision);
        requireId(authorityRevision);
        Access before = authorize(principal, true);
        if (!OWNER.equals(before.actor().role())) {
            throw forbidden();
        }
        requireTargetRole(before, targetId, role);
        Access access = mutationScope(principal, before, targetId);
        String workspace = access.organization().workspaceId();
        Member previous = requireMember(workspace, targetId);
        if (!previous.membershipRevision().equals(membershipRevision)
            || !Objects.equals(previous.authorityRevision(), authorityRevision)) {
            throw stale();
        }
        if (ADMIN.equals(role) && !jdbc.queryForList(
            """
                SELECT id FROM assistant_organization_directory_user WHERE workspace_id = ? AND account_id = ?
                    AND membership_revision = ? AND disabled_at IS NULL
                """,
            workspace, targetId, membershipRevision).isEmpty()) {
            throw new AssistantApiException(HttpStatus.CONFLICT, "ORGANIZATION_DIRECTORY_ROLE",
                "A directory-managed member cannot become an administrator.");
        }
        if (role.equals(previous.role())
            && !journal.blocksOrganizationAuthority(targetId, workspace, authorityRevision)) {
            return previous;
        }
        erasure.retireOrganizationAuthority(targetId, workspace, authorityRevision);
        int changed = jdbc.update(
            """
                UPDATE assistant_workspace_member SET membership_role = ?, organization_authority_revision = ?
                WHERE workspace_id = ? AND account_id = ? AND membership_revision = ? AND organization_authority_revision = ?
                """,
            role, UUID.randomUUID().toString(), workspace, targetId, membershipRevision,
            authorityRevision);
        if (changed != 1) {
            throw stale();
        }
        appendAudit(workspace, "role_changed", access.actor().accountId(), targetId,
            previous.role(), role, null);
        return requireMember(workspace, targetId);
    }

    /**
     * Retires the exact membership generation, including its scoped side effects.
     *
     * @param principal current organization manager
     * @param targetId exact target member
     * @param membershipRevision currently reviewed enrollment generation
     * @param confirm explicit revocation confirmation
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void revoke(Principal principal, String targetId, String membershipRevision,
        boolean confirm) {
        requireId(targetId);
        requireId(membershipRevision);
        if (!confirm) {
            throw invalid("Confirm the exact organization member removal.");
        }
        Access before = authorize(principal, true);
        requireTargetRole(before, targetId, MEMBER);
        Access access = mutationScope(principal, before, targetId);
        String workspace = access.organization().workspaceId();
        Member previous = member(workspace, targetId);
        if (previous == null
            && journal.departedWorkspaceMembership(targetId, workspace, membershipRevision)) {
            return;
        }
        if (previous == null) {
            throw missing();
        }
        requireTargetRole(access, targetId, previous.role());
        if (!previous.membershipRevision().equals(membershipRevision)) {
            throw stale();
        }
        erasure.leaveWorkspace(targetId, workspace, membershipRevision);
        appendAudit(workspace, "member_revoked", access.actor().accountId(), targetId,
            previous.role(), null, null);
    }

    /**
     * Reads recent bounded governance actions visible to a current organization manager.
     *
     * @param principal current owner or administrator
     * @param cursor scoped chronological cursor
     * @param limit bounded page size
     * @return safe audit projection and continuation
     */
    public Page<AuditEntry> audit(Principal principal, String cursor, Integer limit) {
        Access access = authorize(principal, true);
        String workspace = access.organization().workspaceId();
        String after = anchor(workspace, "audit", cursor);
        Instant cutoff = Instant.ofEpochMilli(System.currentTimeMillis() - AUDIT_RETENTION);
        long at = Long.MAX_VALUE;
        String id = "";
        if (!after.isEmpty()) {
            int separator = after.indexOf(':');
            if (separator < 1) {
                throw invalid("Invalid organization audit cursor.");
            }
            id = after.substring(separator + 1);
            requireId(id);
            try {
                at = Long.parseLong(after.substring(0, separator));
            } catch (NumberFormatException failure) {
                throw invalid("Invalid organization audit cursor.");
            }
        }
        int count = pageSize(limit);
        List<AuditEntry> visible = jdbc.query(
            """
                SELECT id, action, actor_account_id, target_account_id, previous_role, role, created_at, directory_id
                FROM assistant_organization_audit
                WHERE workspace_id = ? AND created_at >= ?
                    AND (created_at < ? OR (created_at = ? AND id < ?))
                ORDER BY created_at DESC, id DESC LIMIT ?
                """,
            AUDIT, workspace, cutoff.toEpochMilli(), at, at, id, count + 1);
        recheck(principal, access, true);
        var items = visible.subList(0, Math.min(count, visible.size()));
        String next = visible.size() > count ? encode(workspace, "audit",
            items.getLast().createdAt().toEpochMilli() + ":" + items.getLast().id()) : null;
        var safe = items.stream().map(row -> new AuditEntry(row.id(), row.action(),
            liveAuditActor(row.actorAccountId()), liveAuditActor(row.targetAccountId()),
            row.previousRole(), row.role(), row.createdAt(), row.directoryId())).toList();
        return new Page<>(safe, next);
    }

    /**
     * Reports whether the current workspace member can read aggregate organization usage.
     *
     * @param user current consumer and selected workspace
     * @return whether the consumer is an active manager in this organization
     */
    public boolean usageAvailable(User user) {
        if (!availableIn(user)) {
            return false;
        }
        Member actor = member(user.workspaceId(), user.id());
        return actor != null && !MEMBER.equals(actor.role());
    }

    /**
     * Returns one UTC row for each day in the selected organization's bounded ledger window.
     *
     * @param principal current organization owner or administrator
     * @param requestedDays seven or thirty days, including the current date
     * @return aggregate run and ledger counts without consumer or conversation identifiers
     */
    public Usage usage(Principal principal, Integer requestedDays) {
        Access access = authorize(principal, true);
        int days = requestedDays == null ? 7 : requestedDays;
        if (days != 7 && days != 30) {
            throw invalid("Choose a seven or thirty day organization usage window.");
        }
        Instant now = Instant.now();
        LocalDate end = now.atZone(ZoneOffset.UTC).toLocalDate().plusDays(1);
        LocalDate start = end.minusDays(days);
        var ledger = jdbc.query(
            """
                SELECT FLOOR(r.created_at / 86400000) AS day_number,
                    COUNT(*) AS submitted_runs,
                    SUM(CASE WHEN r.state = 'completed' THEN 1 ELSE 0 END) AS completed_runs,
                    SUM(COALESCE(c.settled_units, 0)) AS settled_units,
                    SUM(CASE WHEN c.settled_units IS NULL THEN COALESCE(c.reserved_units, 0) ELSE 0 END) AS reserved_units
                FROM assistant_run r LEFT JOIN assistant_run_charge c ON r.id = c.run_id AND r.account_id = c.account_id
                WHERE r.workspace_id = ? AND r.created_at >= ? AND r.created_at <= ?
                GROUP BY FLOOR(r.created_at / 86400000)
                """,
            (row, index) -> new UsageDay(LocalDate.ofEpochDay(row.getLong("day_number")),
                row.getLong("submitted_runs"), row.getLong("completed_runs"),
                row.getLong("settled_units"),
                row.getLong("reserved_units")),
            access.organization().workspaceId(),
            start.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(), now.toEpochMilli());
        var indexed =
            ledger.stream().collect(Collectors.toMap(UsageDay::date, Function.identity()));
        recheck(principal, access, true);
        var items = start.datesUntil(end)
            .map(date -> indexed.getOrDefault(date, new UsageDay(date, 0, 0, 0, 0))).toList();
        return new Usage("UTC", days, now, items);
    }

    private String liveAuditActor(String accountId) {
        return accountId == null || journal.blocksCollaborationParticipant(accountId) ? null
            : accountId;
    }

    @Scheduled(initialDelay = 60_000, fixedDelay = 60_000)
    void expireAudit() {
        jdbc.update("""
            DELETE FROM assistant_organization_audit WHERE id IN (
                SELECT id FROM (SELECT id FROM assistant_organization_audit
                    WHERE created_at < ? ORDER BY created_at, id LIMIT 256) expired
            )
            """, System.currentTimeMillis() - AUDIT_RETENTION);
    }

    private Summary provisionRetry(Organization previous, Operator actor, String fingerprint) {
        if (previous == null || previous.tenantId() != actor.tenantId()
            || !previous.provisionDigest().equals(fingerprint)) {
            throw new AssistantApiException(HttpStatus.CONFLICT, "CONFLICT",
                "This organization request key is already in use.");
        }
        requireMember(previous.workspaceId(), previous.ownerId());
        return summary(previous, OWNER);
    }

    private Access mutationScope(Principal principal, Access before, String targetId) {
        lockAccounts(
            Stream.of(before.actor().accountId(), before.organization().ownerId(), targetId)
                .distinct().sorted().toList());
        if (organization(before.organization().workspaceId(), true) == null) {
            throw missing();
        }
        if (jdbc.queryForList(
            """
                SELECT id FROM assistant_session WHERE id = ? AND account_id = ? AND expires_at > ? FOR UPDATE
                """,
            String.class, principal.sessionId(), principal.user().id(), System.currentTimeMillis())
            .isEmpty()) {
            throw unauthorized();
        }
        return recheck(principal, before, true);
    }

    Member lockDirectoryBindingOwner(Principal principal, String targetId) {
        Access before = authorize(principal, true);
        if (!OWNER.equals(before.actor().role())) {
            throw forbidden();
        }
        Access access = mutationScope(principal, before, targetId);
        return directoryMember(access.organization().workspaceId(), targetId);
    }

    Member lockDirectoryMember(String workspace, String targetId) {
        requireReady();
        Organization before = organization(workspace, false);
        if (before == null) {
            throw missing();
        }
        lockAccounts(Stream.of(before.ownerId(), targetId).distinct().sorted().toList());
        if (!before.equals(organization(workspace, true))) {
            throw missing();
        }
        return directoryMember(workspace, targetId);
    }

    Member directoryMember(String workspace, String targetId) {
        Organization subject = organization(workspace, false);
        if (subject == null || subject.ownerId().equals(targetId)
            || !OWNER.equals(requireMember(workspace, subject.ownerId()).role())) {
            return null;
        }
        return jdbc.query(MEMBERSHIP + " AND m.account_id = ? AND m.membership_role = ?", MEMBERS,
            workspace, targetId, MEMBER).stream().filter(
                candidate -> accounts.allowsMembership(
                    targetId, workspace, candidate.membershipRevision()))
            .findFirst().orElse(null);
    }

    void recordDirectoryDeactivation(String workspace, String targetId, String directoryId) {
        appendAudit(workspace, "directory_deactivated", null, targetId, MEMBER, null, null,
            directoryId);
    }

    private void lockAccounts(List<String> accountIds) {
        for (String accountId : accountIds) {
            if (jdbc.queryForList("""
                SELECT id FROM assistant_account WHERE id = ? AND deleted_at IS NULL FOR UPDATE
                """, String.class, accountId).isEmpty() || journal.blocksAccount(accountId)) {
                throw missing();
            }
        }
    }

    private Access recheck(Principal principal, Access before, boolean manager) {
        Access after = authorize(principal, manager);
        if (!before.actor().equals(after.actor())
            || !before.organization().equals(after.organization())) {
            throw stale();
        }
        return after;
    }

    private Access authorize(Principal principal, boolean requireManager) {
        requireReady();
        String workspace = principal.user().workspaceId();
        var organization = organization(workspace, false);
        if (organization == null) {
            throw missing();
        }
        Member owner = requireMember(workspace, organization.ownerId());
        if (!OWNER.equals(owner.role())) {
            throw missing();
        }
        Member actor = requireMember(workspace, principal.user().id());
        if (requireManager && MEMBER.equals(actor.role())) {
            throw forbidden();
        }
        return new Access(organization, actor);
    }

    private Organization organization(String workspace, boolean lock) {
        return jdbc.query(ORG_QUERY + (lock ? " FOR UPDATE" : ""), ORGANIZATION, workspace)
            .stream().findFirst().orElse(null);
    }

    private Member requireMember(String workspace, String accountId) {
        return Objects.requireNonNullElseGet(member(workspace, accountId), () -> {
            throw missing();
        });
    }

    private Member member(String workspace, String accountId) {
        return jdbc.query(MEMBERSHIP + " AND m.account_id = ?", MEMBERS, workspace, accountId)
            .stream()
            .filter(row -> accounts.allowsMembership(row.accountId(), workspace,
                row.membershipRevision()))
            .findFirst().map(row -> effectiveRole(workspace, row)).orElse(null);
    }

    private List<Member> activeMembers(String workspace) {
        return jdbc.query(MEMBERSHIP + " ORDER BY m.account_id LIMIT 129", MEMBERS, workspace)
            .stream()
            .filter(row -> accounts.allowsMembership(row.accountId(), workspace,
                row.membershipRevision()))
            .map(row -> effectiveRole(workspace, row)).toList();
    }

    private Member effectiveRole(String workspace, Member stored) {
        String role = stored.role();
        if (!OWNER.equals(role) && !ADMIN.equals(role) || journal.blocksOrganizationAuthority(
            stored.accountId(), workspace, stored.authorityRevision())) {
            role = MEMBER;
        }
        return new Member(stored.accountId(), stored.username(), role, stored.membershipRevision(),
            stored.authorityRevision());
    }

    private void addMember(String workspace, String accountId, String role) {
        jdbc.update(
            """
                INSERT INTO assistant_workspace_member
                    (workspace_id, account_id, membership_role, membership_revision, organization_authority_revision)
                VALUES (?, ?, ?, ?, ?)
                """,
            workspace, accountId, role, UUID.randomUUID().toString(), UUID.randomUUID().toString());
    }

    private Summary summary(Organization organization, String role) {
        return new Summary(organization.workspaceId(), organization.name(), role,
            organization.seatLimit(),
            activeMembers(organization.workspaceId()).size());
    }

    private void appendAudit(String workspace, String action, String actorId, String targetId,
        String previous, String role, Long operatorId) {
        appendAudit(workspace, action, actorId, targetId, previous, role, operatorId, null);
    }

    private void appendAudit(String workspace, String action, String actorId, String targetId,
        String previous, String role, Long operatorId, String directoryId) {
        jdbc.update(
            """
                INSERT INTO assistant_organization_audit
                    (id, workspace_id, action, actor_account_id, target_account_id, previous_role, role, operator_id, created_at, directory_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
            UUID.randomUUID().toString(), workspace, action, actorId, targetId, previous, role,
            operatorId, System.currentTimeMillis(), directoryId);
        jdbc.update("""
            DELETE FROM assistant_organization_audit WHERE workspace_id = ? AND id IN (
                SELECT id FROM (SELECT id FROM assistant_organization_audit WHERE workspace_id = ?
                    ORDER BY created_at DESC, id DESC LIMIT 256 OFFSET 200) surplus
            )
            """, workspace, workspace);
    }

    private void requireReady() {
        if (!enabled || !journal.enabled()) {
            throw unavailable(HttpStatus.FORBIDDEN);
        }
        if (!available()) {
            throw unavailable(HttpStatus.SERVICE_UNAVAILABLE);
        }
    }

    private static void requireChange(String targetId, String role, boolean confirm) {
        requireId(targetId);
        if (!confirm || !MEMBER.equals(role) && !ADMIN.equals(role)) {
            throw invalid("Confirm a valid organization member role.");
        }
    }

    private static void requireTargetRole(Access access, String targetId, String targetRole) {
        if (targetId.equals(access.organization().ownerId())
            || !OWNER.equals(access.actor().role()) && !MEMBER.equals(targetRole)) {
            throw forbidden();
        }
    }

    private static int pageSize(Integer limit) {
        int count = limit == null ? 25 : limit;
        if (count < 1 || count > MAX_PAGE) {
            throw invalid("Choose an organization page size from 1 to 64.");
        }
        return count;
    }

    private static String encode(String workspace, String section, String anchor) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
            ("1:" + workspace + ":" + section + ":" + anchor).getBytes(StandardCharsets.US_ASCII));
    }

    private static String anchor(String workspace, String section, String cursor) {
        if (cursor == null) {
            return "";
        }
        if (!cursor.isEmpty() && cursor.length() <= 170) {
            try {
                String decoded =
                    new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.US_ASCII);
                String prefix = "1:" + workspace + ":" + section + ":";
                if (decoded.startsWith(prefix) && decoded.length() > prefix.length()) {
                    return decoded.substring(prefix.length());
                }
            } catch (IllegalArgumentException ignored) {
                // The cursor's encoded contents never appear in errors.
            }
        }
        throw invalid("Invalid organization page cursor.");
    }

    private static void requireId(String id) {
        try {
            if (id != null && id.length() == 36 && UUID.fromString(id).toString().equals(id)) {
                return;
            }
        } catch (IllegalArgumentException ignored) {
            // All malformed identifiers receive the same response.
        }
        throw invalid("A valid organization account or generation identifier is required.");
    }

    private static String provisionId(long tenant, String key) {
        byte[] bytes = digest("assistant-organization/v1\n" + tenant + "\n" + key);
        bytes[6] = (byte) ((bytes[6] & 0x0f) | 0x80);
        bytes[8] = (byte) ((bytes[8] & 0x3f) | 0x80);
        var identifier = ByteBuffer.wrap(bytes);
        return new UUID(identifier.getLong(), identifier.getLong()).toString();
    }

    private static byte[] digest(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Organization identity checksum unavailable.",
                exception);
        }
    }

    private static AssistantApiException stale() {
        return new AssistantApiException(HttpStatus.CONFLICT, "ORGANIZATION_MEMBERSHIP_CHANGED",
            "Organization authority changed. Review the current member before continuing.");
    }

    private static AssistantApiException unauthorized() {
        return new AssistantApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED",
            "Sign in to continue.");
    }

    private static AssistantApiException missing() {
        return new AssistantApiException(HttpStatus.NOT_FOUND, "NOT_FOUND",
            "The organization or member was not found.");
    }

    private static AssistantApiException forbidden() {
        return new AssistantApiException(HttpStatus.FORBIDDEN, "FORBIDDEN",
            "This organization action requires a different role.");
    }

    private static AssistantApiException invalid(String message) {
        return new AssistantApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message);
    }

    private static AssistantApiException unavailable(HttpStatus status) {
        return new AssistantApiException(status, "CAPABILITY_UNAVAILABLE",
            "Organization management is unavailable.");
    }

    /**
     * Trusted caller from the distinct ContiNew administration channel.
     *
     * @param operatorId authenticated platform account
     * @param tenantId actual operator tenant
     */
    public record Operator(long operatorId, long tenantId) {
    }

    /**
     * Selected active organization and current local seat counts.
     *
     * @param workspaceId organization scope
     * @param name display name
     * @param role current actor role
     * @param seatLimit fixed local seat allocation
     * @param seatsUsed active enrollment count
     */
    public record Summary(String workspaceId, String name, String role, int seatLimit,
        int seatsUsed) {
    }

    /**
     * Active member metadata visible to authorized organization managers.
     *
     * @param accountId enrolled account
     * @param username visible username
     * @param role current effective role
     * @param membershipRevision exact enrollment generation
     * @param authorityRevision exact current role generation
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Member(String accountId, String username, String role,
        String membershipRevision, String authorityRevision) {
    }

    /**
     * Scoped governance audit entry.
     *
     * @param id action identifier
     * @param action stable action category
     * @param actorAccountId current pseudonymous consumer actor if retained
     * @param targetAccountId current pseudonymous target if retained
     * @param previousRole previous effective role
     * @param role resulting role
     * @param createdAt time the transaction recorded the change
     * @param directoryId trusted machine directory when it performed the action
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AuditEntry(String id, String action, String actorAccountId,
        String targetAccountId,
        String previousRole, String role, Instant createdAt, String directoryId) {
    }

    /**
     * Bounded list and optional continuation.
     *
     * @param items visible rows
     * @param nextCursor next anchor
     * @param <T> projected row type
     */
    public record Page<T>(List<T> items, String nextCursor) {
    }

    /**
     * Aggregate organization ledger projection by run submission date.
     *
     * @param timeZone reporting time zone
     * @param periodDays covered calendar days
     * @param generatedAt time of the observed ledger view
     * @param items consecutive daily rows through the current date
     */
    public record Usage(String timeZone, int periodDays, Instant generatedAt,
        List<UsageDay> items) {
    }

    /**
     * Anonymous counts of the organization runs submitted on one UTC date.
     *
     * @param date run submission date
     * @param submittedRuns all submitted runs
     * @param completedRuns currently completed runs
     * @param settledUnits settled internal ledger units
     * @param reservedUnits still reserved internal ledger units
     */
    public record UsageDay(LocalDate date, long submittedRuns, long completedRuns,
        long settledUnits,
        long reservedUnits) {
    }

    private record Organization(String workspaceId, String ownerId, long tenantId, String name,
        int seatLimit,
        String provisionDigest) {
    }

    private record Access(Organization organization, Member actor) {
    }
}

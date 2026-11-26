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

package top.continew.admin.assistant.account;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import top.continew.admin.assistant.account.AssistantAccounts.Principal;
import top.continew.admin.assistant.privacy.AssistantDeletionJournal;
import top.continew.admin.assistant.privacy.AssistantErasure;

import java.util.UUID;

/**
 * Lets a consumer leave an enrolled workspace with a bound, durable scope.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
@Service
@ConditionalOnProperty(name = "assistant.enabled", havingValue = "true")
public class AssistantWorkspaceDeparture {

    private static final String MEMBERSHIP =
        """
            SELECT m.membership_revision, (
                SELECT w.personal_owner_id FROM assistant_workspace w WHERE w.id = m.workspace_id
            ) AS personal_owner_id, (
                SELECT o.owner_account_id FROM assistant_organization o WHERE o.workspace_id = m.workspace_id
            ) AS organization_owner_id
            FROM assistant_workspace_member m WHERE m.workspace_id = ? AND m.account_id = ?
            """;
    private static final RowMapper<Membership> MEMBER = (row, index) -> new Membership(
        row.getString("membership_revision"), row.getString("personal_owner_id"),
        row.getString("organization_owner_id"));

    private final JdbcTemplate jdbc;
    private final AssistantAccounts accounts;
    private final AssistantDeletionJournal journal;
    private final AssistantErasure erasure;
    private final boolean enabled;

    /**
     * Connects personal device authentication to one independent workspace revocation.
     *
     * @param jdbc current enrollment and session rows
     * @param accounts account lifecycle locks
     * @param journal bound, independent deletion journal
     * @param erasure same-transaction scope retirement
     * @param enabled explicit consumer departure policy
     */
    public AssistantWorkspaceDeparture(JdbcTemplate jdbc, AssistantAccounts accounts,
        AssistantDeletionJournal journal, AssistantErasure erasure,
        @Value("${assistant.workspace-departure.enabled:false}") boolean enabled) {
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.journal = journal;
        this.erasure = erasure;
        this.enabled = enabled;
    }

    /**
     * Returns the current enrolled generation for the user's explicit confirmation.
     *
     * @param principal current private account and same device session
     * @param workspaceId exact target workspace
     * @return self-departure confirmation scope
     */
    public Preview preview(Principal principal, String workspaceId) {
        requireReady();
        requireId(workspaceId);
        String accountId = principal.user().id();
        Membership membership = member(accountId, workspaceId, false);
        if (membership == null || !accounts.allowsMembership(accountId, workspaceId,
            membership.revision())) {
            throw missing();
        }
        requireNonPersonal(accountId, membership);
        return new Preview(workspaceId, membership.revision());
    }

    /**
     * Leaves one exact scope while the account, current device and membership rows are locked.
     *
     * @param principal authenticated consumer and same session
     * @param workspaceId exact target workspace
     * @param revision previously confirmed enrollment generation
     * @param confirm affirmative decision
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void leave(Principal principal, String workspaceId, String revision, boolean confirm) {
        requireReady();
        requireId(workspaceId);
        requireId(revision);
        if (!confirm) {
            throw invalid("Confirm leaving the selected workspace.");
        }
        String accountId = principal.user().id();
        accounts.lockActive(accountId);
        requireSession(principal);
        Membership membership = member(accountId, workspaceId, true);
        if (membership == null) {
            if (journal.departedWorkspaceMembership(accountId, workspaceId, revision)) {
                return;
            }
            throw missing();
        }
        requireNonPersonal(accountId, membership);
        if (!membership.revision().equals(revision)) {
            throw new AssistantApiException(HttpStatus.CONFLICT, "WORKSPACE_MEMBERSHIP_CHANGED",
                "Workspace enrollment changed. Review the current membership before leaving.");
        }
        if (!journal.departedWorkspaceMembership(accountId, workspaceId, revision)) {
            erasure.leaveWorkspace(accountId, workspaceId, revision);
        }
    }

    private Membership member(String accountId, String workspaceId, boolean lock) {
        return jdbc.query(MEMBERSHIP + (lock ? " FOR UPDATE" : ""), MEMBER, workspaceId,
            accountId).stream().findFirst().orElse(null);
    }

    private void requireSession(Principal principal) {
        if (jdbc
            .queryForList("""
                SELECT id FROM assistant_session WHERE id = ? AND account_id = ? AND expires_at > ?
                FOR UPDATE
                """, String.class, principal.sessionId(), principal.user().id(),
                System.currentTimeMillis())
            .isEmpty()) {
            throw new AssistantApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED",
                "Sign in to continue.");
        }
    }

    private void requireReady() {
        if (!enabled || !journal.enabled()) {
            throw new AssistantApiException(HttpStatus.FORBIDDEN, "CAPABILITY_UNAVAILABLE",
                "Workspace departure is unavailable.");
        }
        if (!journal.acceptsErasure()) {
            throw new AssistantApiException(HttpStatus.SERVICE_UNAVAILABLE,
                "CAPABILITY_UNAVAILABLE",
                "Workspace departure is temporarily unavailable.");
        }
    }

    private static void requireNonPersonal(String accountId, Membership membership) {
        if (accountId.equals(membership.personalOwner())) {
            throw new AssistantApiException(HttpStatus.FORBIDDEN, "PERSONAL_WORKSPACE",
                "You cannot leave your own personal workspace.");
        }
        if (accountId.equals(membership.organizationOwner())) {
            throw new AssistantApiException(HttpStatus.FORBIDDEN, "ORGANIZATION_OWNER",
                "The designated organization owner cannot leave this workspace.");
        }
    }

    private static void requireId(String id) {
        try {
            if (id != null && id.length() == 36 && UUID.fromString(id).toString().equals(id)) {
                return;
            }
        } catch (IllegalArgumentException failure) {
            throw invalid("Invalid workspace departure identifier.");
        }
        throw invalid("Invalid workspace departure identifier.");
    }

    private static AssistantApiException missing() {
        return new AssistantApiException(HttpStatus.NOT_FOUND, "NOT_FOUND",
            "Workspace membership was not found.");
    }

    private static AssistantApiException invalid(String message) {
        return new AssistantApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message);
    }

    /**
     * Exact active enrollment presented before leaving.
     *
     * @param workspaceId selected workspace
     * @param membershipRevision current membership generation
     */
    public record Preview(String workspaceId, String membershipRevision) {
    }

    private record Membership(String revision, String personalOwner, String organizationOwner) {
    }
}

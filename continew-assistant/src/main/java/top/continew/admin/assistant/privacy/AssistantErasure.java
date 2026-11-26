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

package top.continew.admin.assistant.privacy;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import top.continew.admin.assistant.account.AssistantAccounts;
import top.continew.admin.assistant.account.AssistantAccounts.Principal;
import top.continew.admin.assistant.account.AssistantAccounts.User;
import top.continew.admin.assistant.account.AssistantApiException;
import top.continew.admin.assistant.privacy.AssistantDeletionJournal.Event;
import top.continew.admin.assistant.privacy.AssistantDeletionJournal.Kind;
import top.continew.admin.assistant.run.AssistantRuns;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * Applies journaled privacy deletion while preserving settlement and moderation references.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
@Service
@ConditionalOnProperty(name = "assistant.enabled", havingValue = "true")
public class AssistantErasure {

    private static final String ERASED_DIGEST = "0".repeat(64);

    private final JdbcTemplate jdbc;
    private final AssistantAccounts accounts;
    private final AssistantRuns runs;
    private final AssistantDeletionJournal journal;
    private final boolean sharesEnabled;

    /**
     * Constructs the consumer privacy erasure service.
     *
     * @param jdbc primary product data store
     * @param accounts current session and password verification
     * @param runs durable run cancellation and settlement
     * @param journal independent deletion intents
     * @param sharesEnabled conversation sharing policy
     */
    public AssistantErasure(JdbcTemplate jdbc, AssistantAccounts accounts, AssistantRuns runs,
        AssistantDeletionJournal journal,
        @Value("${assistant.shares.enabled:false}") boolean sharesEnabled) {
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.runs = runs;
        this.journal = journal;
        this.sharesEnabled = sharesEnabled;
    }

    /**
     * Returns whether the deployment journals privacy operations.
     *
     * @return whether recoverable deletion is configured
     */
    public boolean enabled() {
        return journal.enabled();
    }

    /**
     * Reports whether sharing has an available independent revocation journal.
     *
     * @return whether a link can be shared or read
     */
    public boolean shareReady() {
        return sharesEnabled && journal.acceptsErasure();
    }

    /**
     * Checks durable source and link revocations against the current journal.
     *
     * @param ownerAccountId source owner
     * @param workspaceId source workspace
     * @param conversationId source conversation
     * @param shareId published link identifier
     * @return whether this link must remain inaccessible
     */
    public boolean blocksShare(String ownerAccountId, String workspaceId, String conversationId,
        String shareId) {
        return !sharesEnabled
            || journal.blocksShare(ownerAccountId, workspaceId, conversationId, shareId);
    }

    /**
     * Checks a sharing restriction as soon as its journal record is durable.
     *
     * @param ownerAccountId source owner
     * @param workspaceId source workspace
     * @param conversationId source conversation
     * @return whether the conversation's sharing is restricted
     */
    public boolean sharingRestricted(String ownerAccountId, String workspaceId,
        String conversationId) {
        return journal.sharingRestricted(ownerAccountId, workspaceId, conversationId);
    }

    /**
     * Checks material deletion against the current journal even after a stale concurrent read.
     *
     * @param owner material owner and workspace
     * @param resourceId material identifier
     * @return whether access must be held
     */
    public boolean blocksProjectResource(User owner, String resourceId) {
        return journal.blocksProjectResource(owner.id(), owner.workspaceId(), resourceId);
    }

    /**
     * Removes a freshly reauthenticated consumer account and its private data.
     *
     * @param principal current consumer session
     * @param request current password and explicit deletion intent
     */
    @Transactional
    public void deleteAccount(Principal principal, EraseAccount request) {
        if (!journal.enabled()) {
            throw new AssistantApiException(HttpStatus.FORBIDDEN, "CAPABILITY_UNAVAILABLE",
                "Account erasure is unavailable.");
        }
        if (request == null || !"DELETE".equals(request.confirmation())) {
            throw new AssistantApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                "Explicit confirmation of account deletion is required.");
        }
        accounts.requirePrivacyPassword(principal, request.currentPassword());
        apply(journal.account(principal.user().id()));
    }

    /**
     * Journals deletion of a conversation within its current workspace.
     *
     * @param user authenticated consumer
     * @param conversationId the owned conversation
     */
    @Transactional
    public void deleteConversation(User user, String conversationId) {
        accounts.lockActive(user.id());
        if (jdbc.queryForList("""
            SELECT id FROM assistant_conversation WHERE id = ? AND workspace_id = ?
              AND account_id = ? AND deleted_at IS NULL
            """, conversationId, user.workspaceId(), user.id()).isEmpty()) {
            throw new AssistantApiException(HttpStatus.NOT_FOUND, "NOT_FOUND",
                "Conversation not found.");
        }
        apply(journal.conversation(user.id(), user.workspaceId(), conversationId));
    }

    /**
     * Durably revokes an owned share so an older database backup cannot revive its link.
     *
     * @param principal current consumer and workspace
     * @param shareId owned share
     */
    @Transactional
    public void revokeShare(Principal principal, String shareId) {
        if (!sharesEnabled || !journal.enabled()) {
            throw new AssistantApiException(HttpStatus.FORBIDDEN, "CAPABILITY_UNAVAILABLE",
                "Conversation sharing is unavailable.");
        }
        revokeReportedShare(principal.user().id(), principal.user().workspaceId(), shareId);
    }

    /**
     * Revokes the exact checked share in a previously authorized moderation transaction.
     *
     * @param account owning origin account
     * @param workspace granted origin workspace
     * @param shareId exact independent share generation
     */
    @Transactional(propagation = Propagation.MANDATORY, noRollbackFor = AssistantApiException.class)
    public void revokeReportedShare(String account, String workspace, String shareId) {
        requireRevocationJournal();
        accounts.lockActive(account);
        List<Boolean> revoked = jdbc.query(
            """
                SELECT revoked_at FROM assistant_share WHERE id = ? AND workspace_id = ? AND account_id = ?
                FOR UPDATE
                """,
            (row, index) -> row.getObject("revoked_at") != null, shareId, workspace, account);
        if (revoked.isEmpty()) {
            throw new AssistantApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Share not found.");
        }
        if (!revoked.get(0)) {
            apply(journal.share(account, workspace, shareId));
        }
    }

    /**
     * Persists the restriction and link revocation outside the database backup set.
     * The caller holds the owning account and conversation locks.
     *
     * @param owner source owner in the selected workspace
     * @param conversationId validated source conversation
     * @param restricted whether to restrict or reopen future sharing
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void changeSharingRestriction(User owner, String conversationId, boolean restricted) {
        if (!journal.enabled()) {
            throw new AssistantApiException(HttpStatus.FORBIDDEN, "CAPABILITY_UNAVAILABLE",
                "Conversation sharing restrictions are unavailable.");
        }
        requireRevocationJournal();
        apply(journal.changeSharingRestriction(owner.id(), owner.workspaceId(), conversationId,
            restricted));
    }

    /**
     * Permanently revokes public links before filing a conversation in a private project.
     * The caller holds the owner's account and conversation locks in the filing transaction.
     *
     * @param owner source owner and workspace
     * @param conversationId conversation being filed
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void revokePublicSharesForFiling(User owner, String conversationId) {
        List<String> activeShares = jdbc.queryForList(
            """
                SELECT id FROM assistant_share WHERE workspace_id = ? AND account_id = ?
                    AND conversation_id = ? AND audience = 'link' AND revoked_at IS NULL
                    AND expires_at > ? FOR UPDATE
                """,
            String.class, owner.workspaceId(), owner.id(), conversationId,
            System.currentTimeMillis());
        if (activeShares.isEmpty()) {
            return;
        }
        requireRevocationJournal();
        for (String shareId : activeShares) {
            apply(journal.share(owner.id(), owner.workspaceId(), shareId));
        }
    }

    /**
     * Makes permissions from an earlier placement unusable even after moving back or restoring.
     *
     * @param owner participating conversation owner
     * @param conversationId conversation whose placement is being changed
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void fenceHistoryPlacement(User owner, String conversationId) {
        if (jdbc.queryForList("""
            SELECT run_id FROM assistant_run_history_reference
            WHERE account_id = ? AND workspace_id = ?
                AND (source_conversation_id = ? OR target_conversation_id = ?)
            UNION ALL
            SELECT run_id FROM assistant_run_project_memory_source
            WHERE account_id = ? AND workspace_id = ? AND conversation_id = ?
            UNION ALL
            SELECT id FROM assistant_artifact
            WHERE account_id = ? AND workspace_id = ? AND source_conversation_id = ?
                AND format IN ('sheet', 'slides', 'code', 'image')
            UNION ALL
            SELECT r.id FROM assistant_run r
            JOIN assistant_conversation c ON c.id = r.conversation_id
                AND c.account_id = r.account_id AND c.workspace_id = r.workspace_id
            JOIN assistant_memory_settings s ON s.account_id = c.account_id
                AND s.workspace_id = c.workspace_id AND s.scope_id = c.project_id
                AND s.scope_kind = 'project' AND s.memory_enabled = TRUE
            WHERE r.account_id = ? AND r.workspace_id = ? AND r.conversation_id = ?
                AND r.state = 'running'
            LIMIT 1
            """, String.class, owner.id(), owner.workspaceId(), conversationId,
            conversationId, owner.id(), owner.workspaceId(), conversationId,
            owner.id(), owner.workspaceId(), conversationId,
            owner.id(), owner.workspaceId(), conversationId).isEmpty()) {
            return;
        }
        requireRevocationJournal();
        apply(journal.fenceHistoryPlacement(owner.id(), owner.workspaceId(), conversationId));
    }

    /**
     * Records an owned material deletion before erasing its contents.
     * The caller holds the owning account's lifecycle lock.
     *
     * @param owner authenticated material owner and workspace
     * @param projectId validated private project
     * @param resourceId material identifier
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void deleteProjectResource(User owner, String projectId, String resourceId) {
        List<Boolean> deleted = jdbc.query(
            """
                SELECT deleted_at FROM assistant_project_resource
                WHERE id = ? AND workspace_id = ? AND account_id = ? AND project_id = ?
                FOR UPDATE
                """,
            (row, index) -> row.getObject("deleted_at") != null, resourceId, owner.workspaceId(),
            owner.id(), projectId);
        if (deleted.isEmpty()) {
            throw new AssistantApiException(HttpStatus.NOT_FOUND, "NOT_FOUND",
                "Material not found.");
        }
        if (!deleted.get(0)) {
            apply(journal.projectResource(owner.id(), owner.workspaceId(), resourceId));
        }
    }

    /**
     * Revokes an attachment and durably queues its private objects for removal.
     *
     * @param owner authenticated attachment owner
     * @param projectId owning project
     * @param attachmentId identifier to revoke
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void deleteAttachment(User owner, String projectId, String attachmentId) {
        List<String> statuses = jdbc.queryForList("""
            SELECT status FROM assistant_attachment
            WHERE id = ? AND workspace_id = ? AND account_id = ? AND project_id = ? FOR UPDATE
            """, String.class, attachmentId, owner.workspaceId(), owner.id(), projectId);
        if (statuses.isEmpty()) {
            throw new AssistantApiException(HttpStatus.NOT_FOUND, "NOT_FOUND",
                "Attachment not found.");
        }
        if (!"revoked".equals(statuses.get(0))) {
            apply(journal.attachment(owner.id(), owner.workspaceId(), attachmentId));
        }
    }

    /**
     * Records and scrubs one memory in the owning personal or project scope.
     * The caller holds the owning account's lifecycle lock.
     *
     * @param owner authenticated memory owner and selected workspace
     * @param projectId validated project or null for personal memory
     * @param memoryId memory identifier
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void deleteMemory(User owner, String projectId, String memoryId) {
        String scopeKind = projectId == null ? "personal" : "project";
        String scopeId = projectId == null ? owner.workspaceId() : projectId;
        List<Boolean> deleted = jdbc.query("""
            SELECT deleted_at FROM assistant_memory_entry
            WHERE id = ? AND account_id = ? AND workspace_id = ? AND scope_kind = ? AND scope_id = ?
            FOR UPDATE
            """, (row, index) -> row.getObject("deleted_at") != null,
            memoryId, owner.id(), owner.workspaceId(), scopeKind, scopeId);
        if (deleted.isEmpty()) {
            throw new AssistantApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Memory not found.");
        }
        if (!deleted.get(0)) {
            apply(journal.memoryEntry(owner.id(), owner.workspaceId(), memoryId));
        }
    }

    /**
     * Durably closes a memory scope while the caller holds its account lock.
     *
     * @param owner authenticated memory owner
     * @param projectId validated project or null for personal memory
     * @param epoch prior setting epoch
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void disableMemory(User owner, String projectId, long epoch) {
        apply(journal.memoryOff(owner.id(), owner.workspaceId(), projectId, epoch));
    }

    /**
     * Durably revokes the current personal workspace's history reference.
     *
     * @param owner authenticated memory owner
     * @param epoch prior history setting epoch
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void disableHistoryReference(User owner, long epoch) {
        apply(journal.historyOff(owner.id(), owner.workspaceId(), epoch));
    }

    /**
     * Durably clears a retired personal instruction before an optional replacement.
     *
     * @param owner authenticated memory owner
     * @param epoch prior instruction setting epoch
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void clearMemoryInstructions(User owner, long epoch) {
        apply(journal.instructionsOff(owner.id(), owner.workspaceId(), epoch));
    }

    /**
     * Retires research approval durably while the caller holds its account and task locks.
     *
     * @param accountId research owner
     * @param workspaceId owning workspace
     * @param taskId validated research task
     * @param epoch currently approved version fence
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void revokeResearch(String accountId, String workspaceId, String taskId, long epoch) {
        apply(journal.revokeResearch(accountId, workspaceId, taskId, epoch));
    }

    /**
     * Durably pauses an owned reminder without changing its previous notices.
     *
     * @param owner current owner and selected workspace
     * @param reminderId reminder identifier
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void pauseReminder(User owner, String reminderId) {
        long epoch = lockReminder(owner, reminderId);
        apply(journal.reminderPause(owner.id(), owner.workspaceId(), reminderId, epoch));
    }

    /**
     * Durably erases an owned reminder and its notices.
     *
     * @param owner current owner and selected workspace
     * @param reminderId reminder identifier
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void deleteReminder(User owner, String reminderId) {
        lockReminder(owner, reminderId);
        apply(journal.reminderDelete(owner.id(), owner.workspaceId(), reminderId));
    }

    /**
     * Retires a reminder invitation durably while its owner and share are locked.
     *
     * @param owner authenticated owner of the invitation
     * @param shareId validated and scoped invitation identifier
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void revokeReminderShare(User owner, String shareId) {
        revokeReminderShare(owner.id(), owner.workspaceId(), shareId);
    }

    /**
     * Revokes the exact scoped reminder share in an authorized caller transaction.
     *
     * @param ownerId owning account
     * @param workspaceId owning workspace
     * @param shareId exact template share generation
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void revokeReminderShare(String ownerId, String workspaceId, String shareId) {
        apply(journal.revokeReminderShare(ownerId, workspaceId, shareId));
    }

    /**
     * Permanently retires a project grant after the scoped record is locked.
     *
     * @param ownerId validated project owner
     * @param workspaceId validated workspace
     * @param grantId validated project grant
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void revokeCollaborationGrant(String ownerId, String workspaceId, String grantId) {
        apply(journal.revokeCollaborationGrant(ownerId, workspaceId, grantId));
    }

    /**
     * Erases explicit collaborator text after scoped removal authority is verified.
     *
     * @param ownerId validated project owner
     * @param workspaceId validated workspace
     * @param textId validated text record
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void deleteCollaborationText(String ownerId, String workspaceId, String textId) {
        apply(journal.deleteCollaborationText(ownerId, workspaceId, textId));
    }

    /**
     * Revokes a project artifact version publication after scoped ownership validation.
     *
     * @param ownerId validated project owner
     * @param workspaceId validated workspace
     * @param publicationId validated publication
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void revokeArtifactPublication(String ownerId, String workspaceId,
        String publicationId) {
        apply(journal.revokeArtifactPublication(ownerId, workspaceId, publicationId));
    }

    /**
     * Retires the exact active hosted revision before its pointer changes or its address is revoked.
     *
     * @param owner validated personal project owner
     * @param revision exact release generation
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void retireHostedSite(User owner, String revision) {
        retireHostedSite(owner.id(), owner.workspaceId(), revision);
    }

    /**
     * Retires a specific publish generation in an authorized source transaction.
     *
     * @param ownerId originating account
     * @param workspaceId owning workspace
     * @param revision exact checked release generation
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void retireHostedSite(String ownerId, String workspaceId, String revision) {
        apply(journal.retireHostedSite(ownerId, workspaceId, revision));
    }

    /**
     * Durably withdraws an exact approved or pending catalogue release generation.
     *
     * @param accountId validated release author
     * @param workspaceId validated submission workspace
     * @param releaseId locked exact release generation
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void retireCatalogRelease(String accountId, String workspaceId, String releaseId) {
        if (!journal.catalogReleaseRetired(accountId, workspaceId, releaseId)) {
            apply(journal.retireCatalogRelease(accountId, workspaceId, releaseId));
        }
    }

    /**
     * Retires one exact authorized synthetic guardian consent before replacement or withdrawal.
     *
     * @param participantId scoped participant
     * @param workspaceId exact synthetic fixture workspace
     * @param revision exact reviewed consent generation
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void retireGuardianConsent(String participantId, String workspaceId, String revision) {
        if (!journal.guardianConsentRetired(participantId, workspaceId, revision)) {
            apply(journal.retireGuardianConsent(participantId, workspaceId, revision));
        }
    }

    /**
     * Retires the previous organization policy even if the new row is absent after recovery.
     *
     * @param ownerId designated organization owner
     * @param workspaceId owning organization
     * @param revision exact policy being superseded
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void retireOrganizationPolicy(String ownerId, String workspaceId, String revision) {
        if (!journal.organizationPolicyRetired(ownerId, workspaceId, revision)) {
            apply(journal.retireOrganizationPolicy(ownerId, workspaceId, revision));
        }
    }

    /**
     * Erases one currently authorized publication comment.
     *
     * @param ownerId validated project owner
     * @param workspaceId validated workspace
     * @param commentId validated comment
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void deleteArtifactComment(String ownerId, String workspaceId, String commentId) {
        apply(journal.deleteArtifactComment(ownerId, workspaceId, commentId));
    }

    /**
     * Durably erases an authorized shared draft and its dependent revisions.
     *
     * @param ownerId validated project owner
     * @param workspaceId validated workspace
     * @param draftId locked shared draft
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void deleteSharedDraft(String ownerId, String workspaceId, String draftId) {
        apply(journal.deleteSharedDraft(ownerId, workspaceId, draftId));
    }

    /**
     * Records a self-departure confirmed against the exact locked membership.
     *
     * @param accountId authenticated departing account
     * @param workspaceId departed workspace
     * @param revision exact enrolled membership generation
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void leaveWorkspace(String accountId, String workspaceId, String revision) {
        apply(journal.leaveWorkspace(accountId, workspaceId, revision));
    }

    /**
     * Retires the exact scoped organization sign-in session or consent after local authorization.
     *
     * @param accountId owner of the current organization sign-in scope
     * @param workspaceId selected workspace
     * @param objectId locked credential generation
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void revokeOrganizationSso(String accountId, String workspaceId, String objectId) {
        apply(journal.revokeOrganizationSso(accountId, workspaceId, objectId));
    }

    /**
     * Advances one organization's password-approved link boundary even without a stored consent.
     *
     * @param accountId participant in the active directory binding
     * @param workspaceId exact bound organization
     * @param bindingId immutable directory membership binding
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void fenceOrganizationSsoLink(String accountId, String workspaceId, String bindingId) {
        apply(journal.fenceOrganizationSsoLink(accountId, workspaceId, bindingId));
    }

    /**
     * Retires only the previous organization authority while keeping ordinary membership.
     *
     * @param accountId affected organization member
     * @param workspaceId selected organization workspace
     * @param revision reviewed prior role generation
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void retireOrganizationAuthority(String accountId, String workspaceId, String revision) {
        if (!journal.organizationAuthorityRetired(accountId, workspaceId, revision)) {
            apply(journal.retireOrganizationAuthority(accountId, workspaceId, revision));
        }
    }

    /**
     * Persists an explicitly authenticated conversation read policy transition.
     *
     * @param user current workspace owner
     * @param conversationId already verified conversation scope
     * @param locked desired private read state
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void changeConversationReadLock(User user, String conversationId, boolean locked) {
        apply(journal.changeConversationReadLock(user.id(), user.workspaceId(), conversationId,
            locked));
    }

    /**
     * Invalidates a previous active head independently of primary database backups.
     *
     * @param owner current workspace owner
     * @param assistantId private assistant being revised
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void reviseCustomAssistant(User owner, String assistantId) {
        long epoch = lockCustomAssistant(owner, assistantId);
        apply(journal.customAssistantRevision(owner.id(), owner.workspaceId(), assistantId, epoch));
    }

    /**
     * Permanently revokes a private assistant and its definition history.
     *
     * @param owner current workspace owner
     * @param assistantId private assistant to remove
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void deleteCustomAssistant(User owner, String assistantId) {
        lockCustomAssistant(owner, assistantId);
        apply(journal.customAssistantDelete(owner.id(), owner.workspaceId(), assistantId));
    }

    /**
     * Persists a private draft's revocation before removing any versions.
     *
     * @param owner currently authorized artifact owner
     * @param artifactId private draft to revoke
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void deleteArtifact(User owner, String artifactId) {
        if (jdbc.queryForList(
            """
                SELECT id FROM assistant_artifact WHERE account_id = ? AND workspace_id = ? AND id = ? FOR UPDATE
                """,
            String.class, owner.id(), owner.workspaceId(), artifactId).isEmpty()) {
            throw new AssistantApiException(HttpStatus.NOT_FOUND, "NOT_FOUND",
                "Private artifact not found.");
        }
        apply(journal.artifactDelete(owner.id(), owner.workspaceId(), artifactId));
    }

    private long lockCustomAssistant(User owner, String assistantId) {
        return jdbc.queryForList("""
            SELECT head_epoch FROM assistant_custom_assistant
            WHERE id = ? AND account_id = ? AND workspace_id = ? FOR UPDATE
            """, Long.class, assistantId, owner.id(), owner.workspaceId()).stream().findFirst()
            .orElseThrow(() -> new AssistantApiException(HttpStatus.NOT_FOUND, "NOT_FOUND",
                "Private assistant not found."));
    }

    private long lockReminder(User owner, String reminderId) {
        return jdbc.queryForList("""
            SELECT config_epoch FROM assistant_reminder
            WHERE id = ? AND account_id = ? AND workspace_id = ? FOR UPDATE
            """, Long.class, reminderId, owner.id(), owner.workspaceId()).stream().findFirst()
            .orElseThrow(() -> new AssistantApiException(HttpStatus.NOT_FOUND, "NOT_FOUND",
                "Reminder not found."));
    }

    /**
     * Reapplies a durable deletion to the currently selected database.
     *
     * @param event validated deletion intent
     */
    @Transactional
    public void replay(Event event) {
        // The per-account row uses the same lock order as live enqueue, erasure and conversation creation.
        jdbc.queryForList("SELECT id FROM assistant_account WHERE id = ? FOR UPDATE",
            event.accountId());
        apply(event);
    }

    /**
     * Records a sandbox authorization closure before changing its queued actions.
     *
     * @param owner authorized current workspace owner of the grant
     * @param authorizationId exact immutable grant
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void revokeSandboxConnector(User owner, String authorizationId) {
        sandboxConnectorEvent(owner, Kind.CONNECTOR_REVOKE, authorizationId);
    }

    /**
     * Records the withdrawal of an unsent sandbox action.
     *
     * @param owner action owner
     * @param actionId stable undelivered action
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void cancelSandboxConnectorAction(User owner, String actionId) {
        sandboxConnectorEvent(owner, Kind.CONNECTOR_CANCEL, actionId);
    }

    /**
     * Persists an independent irreversible-dispatch fence before the simulator is called.
     *
     * @param owner authorized action owner
     * @param actionId stable operation
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void startSandboxConnectorAction(User owner, String actionId) {
        sandboxConnectorEvent(owner, Kind.CONNECTOR_START, actionId);
    }

    private void sandboxConnectorEvent(User owner, Kind kind, String target) {
        requireRevocationJournal();
        apply(journal.sandboxConnectorEvent(kind, owner.id(), owner.workspaceId(), target));
    }

    /**
     * Records independent withdrawal of an owned event reminder before its rows are removed.
     *
     * @param owner authenticated event reminder owner
     * @param id event reminder identifier
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void deleteSandboxEventReminder(User owner, String id) {
        requireRevocationJournal();
        apply(journal.sandboxEventReminderDelete(owner.id(), owner.workspaceId(), id));
    }

    /**
     * Records independent withdrawal of the current event-delivery generation.
     *
     * @param owner authenticated event reminder owner
     * @param id event reminder identifier
     * @param epoch verified current scheduling generation
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void pauseSandboxEventReminder(User owner, String id, long epoch) {
        requireRevocationJournal();
        apply(journal.sandboxEventReminderPause(owner.id(), owner.workspaceId(), id, epoch));
    }

    /**
     * Persists a retirement of the current local proactive policy before replacement or disabling.
     *
     * @param owner authenticated policy owner
     * @param epoch exact invalidated generation
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void revokeProactivePreferences(User owner, long epoch) {
        requireRevocationJournal();
        apply(journal.proactivePreferenceOff(owner.id(), owner.workspaceId(), epoch));
    }

    /**
     * Persists a scoped dismissal before changing the suggestion's current state.
     *
     * @param owner authenticated suggestion owner
     * @param id exact suggestion identifier
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void dismissProactiveSuggestion(User owner, String id) {
        requireRevocationJournal();
        apply(journal.proactiveSuggestionDismiss(owner.id(), owner.workspaceId(), id));
    }

    /**
     * Reserves one scoped role and local day before storing a suggestion.
     *
     * @param owner authenticated suggestion owner
     * @param id stable suggestion identifier
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void reserveProactiveSuggestion(User owner, String id) {
        requireRevocationJournal();
        apply(journal.proactiveSuggestionIssue(owner.id(), owner.workspaceId(), id));
    }

    List<String> appliedIds() {
        return jdbc.queryForList("SELECT event_id FROM assistant_deletion_applied", String.class);
    }

    /**
     * Enrolls an unbound database and enforces its immutable journal identity on subsequent boots.
     *
     * @param journalId configured, verified journal ID or null when disabled
     */
    @Transactional
    public void verifyJournalBinding(String journalId) {
        List<String> existing = jdbc.queryForList(
            "SELECT journal_id FROM assistant_deletion_binding WHERE binding_id = 1 FOR UPDATE",
            String.class);
        if (existing.isEmpty()) {
            if (journalId != null) {
                jdbc.update("""
                    INSERT INTO assistant_deletion_binding (binding_id, journal_id, bound_at)
                    VALUES (1, ?, ?)
                    """, journalId, System.currentTimeMillis());
            }
        } else if (journalId == null) {
            throw new IllegalStateException("A deletion journal is required by this database.");
        } else if (!existing.get(0).equals(journalId)) {
            throw new IllegalStateException(
                "The database is bound to a different deletion journal.");
        }
    }

    private void apply(Event event) {
        if (jdbc
            .queryForList("SELECT event_id FROM assistant_deletion_applied WHERE event_id = ?",
                event.id())
            .isEmpty()) {
            switch (event.kind()) {
                case ACCOUNT -> eraseAccount(event);
                case CONVERSATION -> eraseConversation(event);
                case SHARE -> revokePublishedShare(event);
                case SHARE_LOCK, SHARE_UNLOCK -> applySharingRestriction(event);
                case RESOURCE -> eraseProjectResource(event);
                case ATTACHMENT -> eraseAttachment(event);
                case RESEARCH_REVOKE -> revokeResearchApproval(event);
                case REMINDER_DELETE -> eraseReminder(event);
                case REMINDER_PAUSE -> applyReminderPause(event);
                case REMINDER_SHARE -> eraseReminderShare(event);
                case COLLAB_GRANT -> eraseCollaborationGrant(event);
                case COLLAB_TEXT -> eraseCollaborationText(event);
                case CUSTOM_REVISE -> applyCustomAssistantRevision(event);
                case CUSTOM_DELETE -> eraseCustomAssistant(event);
                case ARTIFACT -> eraseArtifact(event);
                case COLLAB_ARTIFACT -> eraseArtifactPublication(event);
                case SITE_REV -> jdbc.update(
                    """
                        DELETE FROM assistant_hosted_site WHERE revision = ? AND owner_id = ? AND workspace_id = ?
                        """,
                    event.targetId(), event.accountId(), event.workspaceId());
                case CAT_REV -> retireRestoredCatalogRelease(event);
                case GUARD_REV -> retireRestoredGuardianConsent(event);
                case COLLAB_COMMENT -> eraseArtifactComment(event);
                case COLLAB_DRAFT -> eraseSharedDraft(event);
                case WORKSPACE_LEAVE -> revokeWorkspaceMembership(event);
                case ORG_SSO_REV -> revokeRestoredOrganizationSso(event);
                case ORG_SSO_FENCE -> fenceRestoredOrganizationSsoLink(event);
                case ORG_ROLE, ORG_POLICY -> {
                    // The independent marker holds the prior authority while retaining its review history.
                }
                case CONNECTOR_REVOKE -> revokeSandboxAuthorization(event);
                case CONNECTOR_CANCEL -> cancelSandboxAction(event);
                case CONNECTOR_START -> markSandboxDispatchUnconfirmed(event);
                case EV_REM_DELETE -> deleteSandboxReminder(event);
                case EV_REM_PAUSE -> pauseSandboxReminder(event);
                case PR_PREF_OFF -> applyProactivePreferenceOff(event);
                case PR_SUG_DISMISS -> applyProactiveDismissal(event);
                case PR_SUG_ISSUED -> {
                    // Only the journal and applied marker are needed to deny a second issuance.
                }
                case CONV_RD_LOCK, CONV_RD_OPEN -> applyConversationReadLock(event);
                case CONV_HIST_FENCE -> runs.cancelForHistoryPlacement(event.accountId(),
                    event.workspaceId(), event.targetId(), journal.historyPlacementRevision(
                        event.accountId(), event.workspaceId(), event.targetId()));
                case MEMORY -> eraseMemoryEntry(event);
                case MEMORY_OFF, PROJECT_MEM_OFF, HISTORY_OFF, INSTRUCTIONS_OFF ->
                    revokeMemoryControl(event);
                default -> throw new IllegalStateException("Unsupported privacy deletion scope.");
            }
            jdbc.update("""
                INSERT INTO assistant_deletion_applied (event_id, kind, applied_at) VALUES (?, ?, ?)
                """, event.id(), event.kind().name(), System.currentTimeMillis());
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {

            @Override
            public void afterCommit() {
                journal.applied(event);
            }
        });
    }

    private void eraseAccount(Event event) {
        String account = event.accountId();
        runs.cancelForPrivacy(account, null);
        jdbc.update("DELETE FROM assistant_session WHERE account_id = ?", account);
        jdbc.update("DELETE FROM assistant_workspace_member WHERE account_id = ?", account);
        jdbc.update("DELETE FROM assistant_organization_directory_user WHERE account_id = ?",
            account);
        jdbc.update(
            """
                UPDATE assistant_organization_audit
                SET actor_account_id = CASE WHEN actor_account_id = ? THEN NULL ELSE actor_account_id END,
                    target_account_id = CASE WHEN target_account_id = ? THEN NULL ELSE target_account_id END
                WHERE actor_account_id = ? OR target_account_id = ?
                """,
            account, account, account, account);
        jdbc.update("""
            UPDATE assistant_account SET deleted_at = COALESCE(deleted_at, ?), username = ?,
                password_hash = '', locale = '', theme = 'system' WHERE id = ?
            """, event.createdAt(), erasedUsername(account), account);
        jdbc.update("""
            UPDATE assistant_project SET deleted_at = COALESCE(deleted_at, ?), name = '',
                description = '' WHERE account_id = ?
            """, event.createdAt(), account);
        jdbc.update("DELETE FROM assistant_custom_assistant WHERE account_id = ?", account);
        jdbc.update("DELETE FROM assistant_catalog_report WHERE account_id = ?", account);
        jdbc.update("DELETE FROM assistant_catalog_entry WHERE account_id = ?", account);
        jdbc.update("""
            DELETE FROM assistant_eligibility_enrollment
            WHERE participant_account_id = ? OR guardian_account_id = ?
            """, account, account);
        jdbc.update("DELETE FROM assistant_artifact WHERE account_id = ?", account);
        jdbc.update("DELETE FROM assistant_project_artifact_comment WHERE author_id = ?", account);
        // Later full-text revisions can retain a contributor's earlier work.
        jdbc.update("""
            DELETE FROM assistant_project_shared_draft WHERE owner_id = ? OR id IN (
                SELECT draft_id FROM assistant_project_shared_draft_revision WHERE author_id = ?
            )
            """, account, account);
        jdbc.update("DELETE FROM assistant_project_resource WHERE account_id = ?", account);
        jdbc.update(
            "DELETE FROM assistant_project_collab_grant WHERE owner_id = ? OR member_id = ?",
            account, account);
        jdbc.update("DELETE FROM assistant_project_collab_text WHERE owner_id = ? OR author_id = ?",
            account, account);
        jdbc.update("DELETE FROM assistant_attachment WHERE account_id = ?", account);
        queueAttachmentCleanup(event, null, null);
        jdbc.update("""
            UPDATE assistant_share SET revoked_at = COALESCE(revoked_at, ?), frozen_title = '',
                snapshot_digest = NULL WHERE account_id = ?
            """, event.createdAt(), account);
        jdbc.update("DELETE FROM assistant_share_copy WHERE account_id = ?", account);
        jdbc.update("DELETE FROM assistant_retrieval_answer WHERE account_id = ?", account);
        jdbc.update("DELETE FROM assistant_research_task WHERE account_id = ?", account);
        jdbc.update("DELETE FROM assistant_sandbox_connector_grant WHERE account_id = ?", account);
        jdbc.update("DELETE FROM assistant_proactive_preference WHERE account_id = ?", account);
        jdbc.update("DELETE FROM assistant_reminder WHERE account_id = ?", account);
        jdbc.update("DELETE FROM assistant_memory_settings WHERE account_id = ?", account);
        scrubContent(event.createdAt(), "account_id = ?", account);
    }

    private void eraseConversation(Event event) {
        String account = event.accountId();
        String conversation = event.targetId();
        if (!jdbc.queryForList(
            """
                SELECT id FROM assistant_conversation WHERE id = ? AND workspace_id = ? AND account_id = ?
                """,
            conversation, event.workspaceId(), account).isEmpty()) {
            runs.cancelForPrivacy(account, conversation);
            runs.cancelForHistorySource(account, event.workspaceId(), conversation);
            jdbc.update("""
                DELETE FROM assistant_artifact
                WHERE account_id = ? AND workspace_id = ? AND source_conversation_id = ?
                """, account, event.workspaceId(), conversation);
            jdbc.update(
                """
                    UPDATE assistant_share SET revoked_at = COALESCE(revoked_at, ?), frozen_title = '',
                        snapshot_digest = NULL WHERE account_id = ? AND workspace_id = ? AND conversation_id = ?
                    """,
                event.createdAt(), account, event.workspaceId(), conversation);
            scrubContent(event.createdAt(), "account_id = ? AND id = ?", account, conversation);
        }
    }

    private void revokePublishedShare(Event event) {
        jdbc.update("""
            UPDATE assistant_share SET revoked_at = COALESCE(revoked_at, ?)
            WHERE id = ? AND workspace_id = ? AND account_id = ?
            """, event.createdAt(), event.targetId(), event.workspaceId(), event.accountId());
    }

    private void applySharingRestriction(Event event) {
        boolean restricted = event.kind() == Kind.SHARE_LOCK;
        jdbc.update("""
            UPDATE assistant_conversation SET sharing_restricted = ?, sharing_policy_revision = ?
            WHERE id = ? AND workspace_id = ? AND account_id = ? AND deleted_at IS NULL
            """, restricted, event.id(), event.targetId(), event.workspaceId(), event.accountId());
        if (restricted) {
            jdbc.update("""
                UPDATE assistant_share SET revoked_at = ?
                WHERE conversation_id = ? AND workspace_id = ? AND account_id = ?
                    AND revoked_at IS NULL
                """, event.createdAt(), event.targetId(), event.workspaceId(), event.accountId());
        }
    }

    private void eraseProjectResource(Event event) {
        jdbc.update(
            """
                DELETE FROM assistant_data_analysis WHERE resource_id = ? AND workspace_id = ? AND account_id = ?
                """,
            event.targetId(), event.workspaceId(), event.accountId());
        jdbc.update("""
            UPDATE assistant_project_resource SET deleted_at = COALESCE(deleted_at, ?), name = '',
                content = '', search_name = '', search_content = ''
            WHERE id = ? AND workspace_id = ? AND account_id = ?
            """, event.createdAt(), event.targetId(), event.workspaceId(), event.accountId());
    }

    private void eraseMemoryEntry(Event event) {
        jdbc.update("""
            UPDATE assistant_memory_entry SET content = '', deleted_at = COALESCE(deleted_at, ?)
            WHERE id = ? AND workspace_id = ? AND account_id = ?
            """, event.createdAt(), event.targetId(), event.workspaceId(), event.accountId());
        jdbc.update("""
            UPDATE assistant_run_project_memory_source SET entry_id = NULL
            WHERE entry_id = ? AND workspace_id = ? AND account_id = ?
            """, event.targetId(), event.workspaceId(), event.accountId());
    }

    private void eraseAttachment(Event event) {
        scrubAttachment(event, event.targetId());
        queueAttachmentCleanup(event, event.workspaceId(), event.targetId());
        for (String id : activeDerivedAttachments(event, "e.source_attachment_id")) {
            scrubDerivedAttachment(event, id);
        }
    }

    private List<String> activeDerivedAttachments(Event event, String anchorColumn) {
        return jdbc.queryForList(
            """
                SELECT a.id FROM assistant_attachment_image_edit e
                JOIN assistant_attachment a ON a.id = e.output_attachment_id
                    AND a.account_id = e.account_id AND a.workspace_id = e.workspace_id AND a.project_id = e.project_id
                WHERE e.account_id = ? AND e.workspace_id = ? AND a.status <> 'revoked' AND
                """
                + anchorColumn + " = ?",
            String.class, event.accountId(), event.workspaceId(), event.targetId());
    }

    private void scrubDerivedAttachment(Event event, String attachmentId) {
        scrubAttachment(event, attachmentId);
        String relatedId = event.id() + "\nimage-edit\n" + attachmentId;
        try {
            String workId = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(relatedId.getBytes(StandardCharsets.US_ASCII)));
            queueAttachmentCleanup(workId, event.accountId(), event.workspaceId(), attachmentId);
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("Attachment cleanup checksum unavailable.", failure);
        }
    }

    private void scrubAttachment(Event event, String attachmentId) {
        jdbc.update(
            """
                DELETE FROM assistant_attachment_grant WHERE attachment_id IN (
                    SELECT id FROM assistant_attachment WHERE id = ? AND workspace_id = ? AND account_id = ?
                )
                """,
            attachmentId, event.workspaceId(), event.accountId());
        jdbc.update(
            """
                UPDATE assistant_attachment SET status = 'revoked', updated_at = ?,
                        original_digest = NULL, approved_digest = NULL, size_bytes = 0, width = 0, height = 0,
                        sample_rate = NULL, channel_count = NULL, duration_millis = NULL,
                        error_code = NULL
                    WHERE id = ? AND workspace_id = ? AND account_id = ?
                    """,
            event.createdAt(), attachmentId, event.workspaceId(), event.accountId());
    }

    private void queueAttachmentCleanup(Event event, String workspaceId, String attachmentId) {
        queueAttachmentCleanup(event.id(), event.accountId(), workspaceId, attachmentId);
    }

    private void queueAttachmentCleanup(String workId, String accountId, String workspaceId,
        String attachmentId) {
        jdbc.update(
            """
                INSERT INTO assistant_attachment_cleanup (event_id, account_id, workspace_id, attachment_id, due_at)
                VALUES (?, ?, ?, ?, ?)
                """,
            workId, accountId, workspaceId, attachmentId, System.currentTimeMillis());
    }

    private void eraseReminder(Event event) {
        jdbc.update("""
            DELETE FROM assistant_reminder WHERE id = ? AND account_id = ? AND workspace_id = ?
            """, event.targetId(), event.accountId(), event.workspaceId());
    }

    private void eraseReminderShare(Event event) {
        jdbc.update("""
            UPDATE assistant_reminder_share SET revoked_at = COALESCE(revoked_at, ?)
            WHERE id = ? AND account_id = ? AND workspace_id = ?
            """, event.createdAt(), event.targetId(), event.accountId(), event.workspaceId());
    }

    private void eraseCollaborationGrant(Event event) {
        jdbc.update("""
            UPDATE assistant_project_collab_grant
            SET state = 'revoked', revoked_at = COALESCE(revoked_at, ?)
            WHERE owner_id = ? AND workspace_id = ? AND id = ?
            """, event.createdAt(), event.accountId(), event.workspaceId(), event.targetId());
    }

    private void eraseCollaborationText(Event event) {
        jdbc.update(
            """
                DELETE FROM assistant_project_collab_text WHERE owner_id = ? AND workspace_id = ? AND id = ?
                """,
            event.accountId(), event.workspaceId(), event.targetId());
    }

    private void applyReminderPause(Event event) {
        jdbc.update(
            """
                UPDATE assistant_reminder SET status = 'paused', config_epoch = ?,
                    next_slot_day = NULL, next_wake_at = NULL, updated_at = ?
                WHERE id = ? AND account_id = ? AND workspace_id = ? AND status = 'active' AND config_epoch <= ?
                """,
            event.createdAt(), event.createdAt(), event.targetId(), event.accountId(),
            event.workspaceId(), event.createdAt());
    }

    private void applyCustomAssistantRevision(Event event) {
        jdbc.update("""
            UPDATE assistant_custom_assistant SET head_epoch = ?, updated_at = ?
            WHERE id = ? AND account_id = ? AND workspace_id = ? AND head_epoch <= ?
            """, event.createdAt(), event.createdAt(), event.targetId(), event.accountId(),
            event.workspaceId(), event.createdAt());
    }

    private void eraseCustomAssistant(Event event) {
        jdbc.update("""
            DELETE FROM assistant_catalog_entry WHERE source_id = ? AND account_id = ?
                AND workspace_id = ? AND kind = 'assistant'
            """, event.targetId(), event.accountId(), event.workspaceId());
        jdbc.update(
            """
                DELETE FROM assistant_custom_assistant WHERE id = ? AND account_id = ? AND workspace_id = ?
                """,
            event.targetId(), event.accountId(), event.workspaceId());
    }

    private void retireRestoredCatalogRelease(Event event) {
        jdbc.update("""
            UPDATE assistant_catalog_entry SET active_release_id = NULL
            WHERE account_id = ? AND workspace_id = ? AND active_release_id = ?
            """, event.accountId(), event.workspaceId(), event.targetId());
        jdbc.update("""
            UPDATE assistant_catalog_release SET state = 'withdrawn',
                decided_at = COALESCE(decided_at, ?)
            WHERE id = ? AND workspace_id = ? AND state IN ('pending', 'published')
                AND entry_id IN (
                    SELECT id FROM assistant_catalog_entry WHERE account_id = ? AND workspace_id = ?
                )
            """, event.createdAt(), event.targetId(), event.workspaceId(), event.accountId(),
            event.workspaceId());
    }

    private void eraseArtifact(Event event) {
        jdbc.update("""
            DELETE FROM assistant_artifact WHERE id = ? AND account_id = ? AND workspace_id = ?
            """, event.targetId(), event.accountId(), event.workspaceId());
    }

    private void eraseArtifactPublication(Event event) {
        jdbc.update("""
            DELETE FROM assistant_project_artifact_publication
            WHERE id = ? AND owner_id = ? AND workspace_id = ?
            """, event.targetId(), event.accountId(), event.workspaceId());
    }

    private void revokeResearchApproval(Event event) {
        jdbc.update("""
            UPDATE assistant_research_task
            SET state = 'cancelled', progress = 0, approval_epoch = NULL, due_at = NULL,
                error_code = NULL, updated_at = CASE WHEN updated_at < ? THEN ? ELSE updated_at END
            WHERE id = ? AND workspace_id = ? AND account_id = ? AND approval_epoch <= ?
            """, event.createdAt(), event.createdAt(), event.targetId(), event.workspaceId(),
            event.accountId(), event.createdAt());
    }

    private void revokeMemoryControl(Event event) {
        String assignment = switch (event.kind()) {
            case MEMORY_OFF, PROJECT_MEM_OFF -> "memory_enabled = FALSE, memory_epoch = ?";
            case HISTORY_OFF -> "reference_history = FALSE, history_epoch = ?";
            case INSTRUCTIONS_OFF -> "instructions = '', instructions_epoch = ?";
            default -> throw new IllegalStateException("Invalid memory revocation.");
        };
        String epoch = switch (event.kind()) {
            case MEMORY_OFF, PROJECT_MEM_OFF -> "memory_epoch";
            case HISTORY_OFF -> "history_epoch";
            case INSTRUCTIONS_OFF -> "instructions_epoch";
            default -> throw new IllegalStateException("Invalid memory revocation.");
        };
        String scopeKind = event.kind() == AssistantDeletionJournal.Kind.PROJECT_MEM_OFF
            ? "project" : "personal";
        jdbc.update("UPDATE assistant_memory_settings SET " + assignment
            + " WHERE account_id = ? AND workspace_id = ? AND scope_kind = ? AND scope_id = ?"
            + " AND " + epoch + " <= ?", event.createdAt(), event.accountId(), event.workspaceId(),
            scopeKind, event.targetId(), event.createdAt());
        if (event.kind() == Kind.HISTORY_OFF) {
            runs.cancelForHistoryOptOut(event.accountId(), event.workspaceId(), event.createdAt());
        }
    }

    private void requireRevocationJournal() {
        if (!journal.acceptsErasure()) {
            throw new AssistantApiException(HttpStatus.SERVICE_UNAVAILABLE,
                "CAPABILITY_UNAVAILABLE", "The share revocation journal is unavailable.");
        }
    }

    private void scrubContent(long deletedAt, String conversationScope, Object... scope) {
        String matchingConversations =
            "SELECT id FROM assistant_conversation WHERE " + conversationScope;
        String runScope = " WHERE conversation_id IN (" + matchingConversations + ")";
        jdbc.update("DELETE FROM assistant_run_history_reference WHERE target_conversation_id IN ("
            + matchingConversations + ")", scope);
        jdbc.update("DELETE FROM assistant_run_project_memory_source WHERE conversation_id IN ("
            + matchingConversations + ")", scope);
        jdbc.update("""
            UPDATE assistant_run_history_reference
            SET source_conversation_id = NULL, source_message_id = NULL,
                source_read_revision = NULL, source_placement_revision = NULL
            WHERE source_conversation_id IN (
            """ + matchingConversations + ")", scope);
        jdbc.update("DELETE FROM assistant_job" + runScope, scope);
        jdbc.update("DELETE FROM assistant_run_event WHERE run_id IN (SELECT id FROM assistant_run"
            + runScope + ")", scope);
        Object[] runArguments = new Object[scope.length + 1];
        runArguments[0] = ERASED_DIGEST;
        System.arraycopy(scope, 0, runArguments, 1, scope.length);
        jdbc.update("""
            UPDATE assistant_run SET content = '', error_message = NULL, request_digest = ?,
                first_sequence = last_sequence + 1,
                idempotency_key = CONCAT(REPLACE(id, '-', ''), '00000000000000000000000000000000')
            """ + runScope, runArguments);
        jdbc.update("UPDATE assistant_chat_message SET content = ''" + runScope, scope);
        Object[] conversationArguments = new Object[scope.length + 1];
        conversationArguments[0] = deletedAt;
        System.arraycopy(scope, 0, conversationArguments, 1, scope.length);
        jdbc.update("""
            UPDATE assistant_conversation SET deleted_at = COALESCE(deleted_at, ?), title = '',
                title_search = '', pinned = FALSE, archived = FALSE, project_id = NULL
            """ + "WHERE " + conversationScope, conversationArguments);
    }

    private void eraseArtifactComment(Event event) {
        jdbc.update(
            """
                DELETE FROM assistant_project_artifact_comment WHERE id = ? AND publication_id IN (
                    SELECT id FROM assistant_project_artifact_publication WHERE owner_id = ? AND workspace_id = ?
                )
                """,
            event.targetId(), event.accountId(), event.workspaceId());
    }

    private void eraseSharedDraft(Event event) {
        jdbc.update(
            """
                DELETE FROM assistant_project_shared_draft WHERE id = ? AND owner_id = ? AND workspace_id = ?
                """,
            event.targetId(), event.accountId(), event.workspaceId());
    }

    private void retireRestoredGuardianConsent(Event event) {
        jdbc.update("""
            UPDATE assistant_eligibility_enrollment SET consent_until = NULL, updated_at = ?
            WHERE participant_account_id = ? AND workspace_id = ? AND consent_revision = ?
            """, event.createdAt(), event.accountId(), event.workspaceId(), event.targetId());
    }

    private void revokeRestoredOrganizationSso(Event event) {
        jdbc.update("""
            DELETE FROM assistant_organization_sso_session WHERE id = ? AND consent_id IN (
                SELECT c.id FROM assistant_organization_sso_consent c
                JOIN assistant_organization_directory_user d ON d.id = c.binding_id
                WHERE d.account_id = ? AND d.workspace_id = ?
            )
            """, event.targetId(), event.accountId(), event.workspaceId());
        jdbc.update(
            """
                DELETE FROM assistant_organization_sso_consent WHERE id = ? AND binding_id IN (
                    SELECT id FROM assistant_organization_directory_user WHERE account_id = ? AND workspace_id = ?
                )
                """,
            event.targetId(), event.accountId(), event.workspaceId());
    }

    private void fenceRestoredOrganizationSsoLink(Event event) {
        String fence = journal.organizationSsoLinkFence(event.accountId(), event.workspaceId(),
            event.targetId());
        jdbc.update("""
            DELETE FROM assistant_organization_sso_consent
            WHERE (link_fence IS NULL OR link_fence <> ?) AND binding_id IN (
                SELECT id FROM assistant_organization_directory_user
                WHERE id = ? AND workspace_id = ? AND account_id = ?
            )
            """, fence, event.targetId(), event.workspaceId(), event.accountId());
    }

    private void revokeWorkspaceMembership(Event event) {
        String account = event.accountId();
        String workspace = event.workspaceId();
        int removed = jdbc.update("""
            DELETE FROM assistant_workspace_member WHERE workspace_id = ? AND account_id = ?
                AND membership_revision = ? AND NOT EXISTS (
                    SELECT 1 FROM assistant_workspace WHERE id = ? AND personal_owner_id = ?
                )
            """, workspace, account, event.targetId(), workspace, account);
        jdbc.update("""
            DELETE FROM assistant_organization_sso_consent WHERE binding_id IN (
                SELECT id FROM assistant_organization_directory_user
                WHERE account_id = ? AND workspace_id = ? AND membership_revision = ?
            )
            """, account, workspace, event.targetId());
        jdbc.update("""
            DELETE FROM assistant_catalog_entry WHERE account_id = ? AND workspace_id = ?
                AND membership_revision = ?
            """, account, workspace, event.targetId());
        jdbc.update("""
            DELETE FROM assistant_eligibility_enrollment WHERE workspace_id = ? AND (
                (participant_account_id = ? AND participant_revision = ?)
                OR (guardian_account_id = ? AND guardian_revision = ?))
            """, workspace, account, event.targetId(), account, event.targetId());
        for (String id : activeDerivedAttachments(event, "e.membership_revision")) {
            scrubDerivedAttachment(event, id);
        }
        if (removed == 0) {
            // A later enrollment has a fresh generation and must keep its current authorization.
            return;
        }
        runs.cancelForWorkspaceDeparture(account, workspace);
        jdbc.update("""
            UPDATE assistant_proactive_preference SET enabled = FALSE, next_check_at = NULL
            WHERE account_id = ? AND workspace_id = ? AND membership_revision = ?
            """, account, workspace, event.targetId());
        withdrawSandboxOccurrences(account, workspace, null);
        jdbc.update("""
            UPDATE assistant_sandbox_connector_grant SET revoked_at = COALESCE(revoked_at, ?)
            WHERE account_id = ? AND workspace_id = ?
            """, event.createdAt(), account, workspace);
        jdbc.update("""
            UPDATE assistant_sandbox_connector_action SET state = 'blocked', updated_at = ?
            WHERE account_id = ? AND workspace_id = ? AND state IN ('awaiting_approval', 'approved')
            """, event.createdAt(), account, workspace);
        closeJobs(account, workspace, null, event.createdAt());
        jdbc.update("""
            UPDATE assistant_share SET revoked_at = ?
            WHERE workspace_id = ? AND account_id = ? AND revoked_at IS NULL
            """, event.createdAt(), workspace, account);
        jdbc.update("""
            UPDATE assistant_project_collab_grant SET state = 'revoked', revoked_at = ?
            WHERE workspace_id = ? AND (owner_id = ? OR member_id = ?) AND state <> 'revoked'
            """, event.createdAt(), workspace, account, account);
        jdbc.update("""
            DELETE FROM assistant_attachment_grant
            WHERE session_id IN (SELECT id FROM assistant_session WHERE account_id = ?)
                AND attachment_id IN (SELECT id FROM assistant_attachment WHERE workspace_id = ?)
            """, account, workspace);
        jdbc.update("""
            UPDATE assistant_research_task
            SET state = 'cancelled', progress = 0, approval_epoch = NULL, due_at = NULL,
                error_code = NULL, updated_at = ?
            WHERE account_id = ? AND workspace_id = ? AND state IN ('queued', 'running')
            """, event.createdAt(), account, workspace);
        jdbc.update("""
            UPDATE assistant_reminder SET status = 'paused', next_slot_day = NULL,
                next_wake_at = NULL, updated_at = ?
            WHERE account_id = ? AND workspace_id = ? AND status = 'active'
            """, event.createdAt(), account, workspace);
        jdbc.update("""
            UPDATE assistant_reminder_share SET revoked_at = ?
            WHERE account_id = ? AND workspace_id = ? AND revoked_at IS NULL
            """, event.createdAt(), account, workspace);
    }

    private void revokeSandboxAuthorization(Event event) {
        jdbc.update("""
            UPDATE assistant_sandbox_connector_grant SET revoked_at = COALESCE(revoked_at, ?)
            WHERE id = ? AND account_id = ? AND workspace_id = ?
            """, event.createdAt(), event.targetId(), event.accountId(), event.workspaceId());
        withdrawSandboxOccurrences(event.accountId(), event.workspaceId(), event.targetId());
        jdbc.update("""
            UPDATE assistant_sandbox_connector_action SET state = 'blocked', updated_at = ?
            WHERE authorization_id = ? AND account_id = ? AND workspace_id = ?
                AND state IN ('awaiting_approval', 'approved')
            """, event.createdAt(), event.targetId(), event.accountId(), event.workspaceId());
    }

    private void withdrawSandboxOccurrences(String account, String workspace,
        String authorization) {
        String update =
            """
                UPDATE assistant_sandbox_event_occurrence SET state = 'withdrawn'
                WHERE state = 'waiting' AND reminder_id IN (
                    SELECT id FROM assistant_sandbox_event_reminder WHERE account_id = ? AND workspace_id = ?
                """;
        if (authorization == null) {
            jdbc.update(update + ")", account, workspace);
        } else {
            jdbc.update(update + " AND authorization_id = ?)", account, workspace, authorization);
        }
    }

    private void deleteSandboxReminder(Event event) {
        jdbc.update("""
            DELETE FROM assistant_sandbox_event_reminder
            WHERE id = ? AND account_id = ? AND workspace_id = ?
            """, event.targetId(), event.accountId(), event.workspaceId());
    }

    private void pauseSandboxReminder(Event event) {
        jdbc.update("""
            UPDATE assistant_sandbox_event_reminder SET active = FALSE, config_epoch = ?,
                updated_at = CASE WHEN updated_at < ? THEN ? ELSE updated_at END
            WHERE id = ? AND account_id = ? AND workspace_id = ? AND config_epoch <= ?
            """, event.createdAt(), event.createdAt(), event.createdAt(), event.targetId(),
            event.accountId(), event.workspaceId(), event.createdAt());
        jdbc.update(
            """
                UPDATE assistant_sandbox_event_occurrence SET state = 'withdrawn'
                WHERE state = 'waiting' AND subscription_epoch <= ? AND reminder_id IN (
                    SELECT id FROM assistant_sandbox_event_reminder WHERE id = ? AND account_id = ? AND workspace_id = ?
                )
                """,
            event.createdAt(), event.targetId(), event.accountId(), event.workspaceId());
    }

    private void cancelSandboxAction(Event event) {
        jdbc.update("""
            UPDATE assistant_sandbox_connector_action SET state = 'cancelled', updated_at = ?
            WHERE id = ? AND account_id = ? AND workspace_id = ?
                AND state IN ('awaiting_approval', 'approved')
            """, event.createdAt(), event.targetId(), event.accountId(), event.workspaceId());
    }

    private void markSandboxDispatchUnconfirmed(Event event) {
        jdbc.update("""
            UPDATE assistant_sandbox_connector_action
            SET state = 'pending_result', started_at = ?, updated_at = ?
            WHERE id = ? AND account_id = ? AND workspace_id = ?
                AND state IN ('awaiting_approval', 'approved')
            """, event.createdAt(), event.createdAt(), event.targetId(), event.accountId(),
            event.workspaceId());
    }

    private void applyProactivePreferenceOff(Event event) {
        jdbc.update("""
            UPDATE assistant_proactive_preference SET enabled = FALSE, next_check_at = NULL,
                policy_epoch = ?, updated_at = CASE WHEN updated_at < ? THEN ? ELSE updated_at END
            WHERE account_id = ? AND workspace_id = ? AND policy_epoch <= ?
            """, event.createdAt(), event.createdAt(), event.createdAt(), event.accountId(),
            event.workspaceId(), event.createdAt());
    }

    private void applyProactiveDismissal(Event event) {
        jdbc.update("""
            UPDATE assistant_proactive_suggestion SET dismissed = TRUE
            WHERE id = ? AND account_id = ? AND workspace_id = ?
            """, event.targetId(), event.accountId(), event.workspaceId());
    }

    private void applyConversationReadLock(Event event) {
        boolean locked = event.kind() == Kind.CONV_RD_LOCK;
        String account = event.accountId();
        String workspace = event.workspaceId();
        String conversation = event.targetId();
        if (locked) {
            // Finalization holds a run before its conversation. Preserve that order here.
            runs.cancelForPrivacy(account, conversation);
            runs.cancelForHistorySource(account, workspace, conversation);
            closeJobs(account, workspace, conversation, event.createdAt());
        }
        jdbc.update("""
            UPDATE assistant_conversation SET read_locked = ?, read_lock_revision = ?
            WHERE id = ? AND account_id = ? AND workspace_id = ? AND deleted_at IS NULL
            """, locked, event.id(), conversation, account, workspace);
        if (locked) {
            jdbc.update(
                """
                    UPDATE assistant_share SET revoked_at = ?
                    WHERE conversation_id = ? AND account_id = ? AND workspace_id = ? AND revoked_at IS NULL
                    """,
                event.createdAt(), conversation, account, workspace);
            jdbc.update("""
                DELETE FROM assistant_project_artifact_publication
                WHERE workspace_id = ? AND owner_id = ? AND artifact_id IN (
                    SELECT id FROM assistant_artifact
                    WHERE source_conversation_id = ? AND account_id = ? AND workspace_id = ?
                )
                """, workspace, account, conversation, account, workspace);
        }
    }

    private void closeJobs(String accountId, String workspaceId, String conversationId, long time) {
        String query = """
            SELECT id FROM assistant_job WHERE account_id = ? AND workspace_id = ?
                AND status IN ('queued', 'running')
            """;
        String order = " ORDER BY id FOR UPDATE";
        List<String> active = conversationId == null
            ? jdbc.queryForList(query + order, String.class, accountId, workspaceId)
            : jdbc.queryForList(query + " AND conversation_id = ?" + order, String.class,
                accountId, workspaceId, conversationId);
        for (String id : active) {
            jdbc.update("""
                UPDATE assistant_job SET status = 'cancelled', error_code = NULL, updated_at = ?
                WHERE id = ?
                """, time, id);
            jdbc.update("DELETE FROM assistant_job_outbox WHERE job_id = ?", id);
        }
    }

    private static String erasedUsername(String accountId) {
        return "~er-" + new BigInteger(accountId.replace("-", ""), 16).toString(36);
    }

    /**
     * Current password and explicit intent for account erasure.
     *
     * @param currentPassword current consumer password
     * @param confirmation exact deletion confirmation
     */
    public record EraseAccount(String currentPassword, String confirmation) {

        @Override
        public String toString() {
            return "EraseAccount[protected]";
        }
    }
}

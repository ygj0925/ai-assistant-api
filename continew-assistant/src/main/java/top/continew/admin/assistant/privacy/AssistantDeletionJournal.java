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
import org.springframework.stereotype.Component;
import top.continew.admin.assistant.account.AssistantApiException;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Fsynced deletion intents outside the primary database restore set.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
@Component
@ConditionalOnProperty(name = "assistant.enabled", havingValue = "true")
public class AssistantDeletionJournal {

    private static final String HEADER = "assistant-deletion-journal-v1 ";
    private static final int MAX_BYTES = 16 * 1024 * 1024;
    private static final String NONE = "-";

    private final boolean enabled;
    private final String identity;
    private final Map<String, Event> entries = new LinkedHashMap<>();
    private final Map<String, Event> pending = new LinkedHashMap<>();
    private final Set<Scope> denied = new HashSet<>();
    private final Map<Scope, Long> memoryRevocations = new HashMap<>();
    private final Map<Scope, Long> researchRevocations = new HashMap<>();
    private final Map<Scope, Long> reminderPauses = new HashMap<>();
    private final Map<Scope, Long> customAssistantRevisions = new HashMap<>();
    private final Map<Scope, String> sharingPolicyRevisions = new HashMap<>();
    private final Map<Scope, String> conversationReadRevisions = new HashMap<>();
    private final Map<Scope, String> historyPlacementRevisions = new HashMap<>();
    private final Map<Scope, String> organizationSsoLinkFences = new HashMap<>();
    private final Map<Workspace, String> workspaceReadRevisions = new HashMap<>();
    private FileChannel channel;
    private FileLock writerLock;
    private String tip;
    private boolean healthy;
    private boolean ready;

    /**
     * Opens and exclusively locks a pre-provisioned journal when deletion replay is enabled.
     *
     * @param enabled whether recoverable privacy deletion is enabled
     * @param path externally managed journal path
     * @param journalId identity recorded in the provisioned header
     */
    public AssistantDeletionJournal(
        @Value("${assistant.privacy.erasure-enabled:false}") boolean enabled,
        @Value("${assistant.privacy.journal-path:}") String path,
        @Value("${assistant.privacy.journal-id:}") String journalId) {
        this.enabled = enabled;
        this.identity = journalId;
        if (enabled) {
            open(path, journalId);
        }
    }

    /**
     * Returns whether journal-backed deletion is configured.
     *
     * @return feature flag
     */
    public boolean enabled() {
        return enabled;
    }

    String identity() {
        return identity;
    }

    /**
     * Reports whether the journal can durably accept a new deletion.
     *
     * @return whether recoverable erasure is available
     */
    public synchronized boolean acceptsErasure() {
        return enabled && writable() && ready;
    }

    /**
     * Blocks fresh consumer requests while their durable deletion is unresolved.
     *
     * @param accountId consumer identifier
     * @return whether reads and writes must be held
     */
    public synchronized boolean blocksAccount(String accountId) {
        return enabled && (!writable() || !ready || pending.values().stream()
            .anyMatch(event -> event.kind() != Kind.CONNECTOR_START
                && event.kind() != Kind.PR_SUG_ISSUED
                && event.accountId().equals(accountId)));
    }

    /**
     * Holds a source share while the journal is unavailable or records its revocation.
     *
     * @param accountId source owner
     * @param workspaceId source workspace
     * @param conversationId source conversation
     * @param shareId published link identifier
     * @return whether this link must remain inaccessible
     */
    synchronized boolean blocksShare(String accountId, String workspaceId, String conversationId,
        String shareId) {
        // A concurrent reader may still hold a snapshot older than an applied revocation.
        return !acceptsErasure()
            || denied.contains(new Scope(Kind.ACCOUNT, accountId, NONE, accountId))
            || denied.contains(new Scope(Kind.CONVERSATION, accountId, workspaceId, conversationId))
            || conversationReadLocked(accountId, workspaceId, conversationId)
            || denied.contains(new Scope(Kind.SHARE, accountId, workspaceId, shareId));
    }

    synchronized boolean sharingRestricted(String accountId, String workspaceId,
        String conversationId) {
        return !acceptsErasure()
            || denied.contains(new Scope(Kind.SHARE_LOCK, accountId, workspaceId, conversationId));
    }

    synchronized boolean blocksProjectResource(String accountId, String workspaceId,
        String resourceId) {
        return !acceptsErasure()
            || denied.contains(new Scope(Kind.ACCOUNT, accountId, NONE, accountId))
            || denied.contains(new Scope(Kind.RESOURCE, accountId, workspaceId, resourceId));
    }

    /**
     * Holds an attachment when the journal is unavailable or an applicable revocation exists.
     *
     * @param accountId attachment owner
     * @param workspaceId owning workspace
     * @param attachmentId attachment identifier
     * @return whether the attachment must remain inaccessible
     */
    public synchronized boolean blocksAttachment(String accountId, String workspaceId,
        String attachmentId) {
        return !acceptsErasure()
            || denied.contains(new Scope(Kind.ACCOUNT, accountId, NONE, accountId))
            || denied.contains(new Scope(Kind.ATTACHMENT, accountId, workspaceId, attachmentId));
    }

    /**
     * Finds whether a single saved memory was revoked after its database snapshot was read.
     *
     * @param accountId memory owner
     * @param workspaceId owning workspace
     * @param memoryId memory identifier
     * @return whether the record must remain inaccessible
     */
    public synchronized boolean blocksMemoryEntry(String accountId, String workspaceId,
        String memoryId) {
        return !acceptsErasure()
            || denied.contains(new Scope(Kind.ACCOUNT, accountId, NONE, accountId))
            || denied.contains(new Scope(Kind.MEMORY, accountId, workspaceId, memoryId));
    }

    /**
     * Reads revocation cutoffs for a selected memory scope in one journal snapshot.
     *
     * @param accountId memory owner
     * @param workspaceId owning workspace
     * @param projectId selected project or null for personal controls
     * @return latest durable policy cutoffs
     */
    public synchronized MemoryRevocations memoryRevocations(String accountId, String workspaceId,
        String projectId) {
        if (!acceptsErasure()
            || denied.contains(new Scope(Kind.ACCOUNT, accountId, NONE, accountId))) {
            return new MemoryRevocations(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE);
        }
        Kind kind = projectId == null ? Kind.MEMORY_OFF : Kind.PROJECT_MEM_OFF;
        String scopeId = projectId == null ? workspaceId : projectId;
        return new MemoryRevocations(
            memoryRevocations.getOrDefault(new Scope(kind, accountId, workspaceId, scopeId), 0L),
            memoryRevocations
                .getOrDefault(new Scope(Kind.HISTORY_OFF, accountId, workspaceId, workspaceId), 0L),
            memoryRevocations.getOrDefault(
                new Scope(Kind.INSTRUCTIONS_OFF, accountId, workspaceId, workspaceId), 0L));
    }

    synchronized boolean allowsExportSnapshot(String accountId, String workspaceId,
        Collection<String> conversationIds, Collection<String> resourceIds,
        Collection<String> memoryEntryIds, Collection<String> attachmentIds,
        Collection<ExportMemoryPolicy> policies) {
        if (!enabled) {
            return resourceIds.isEmpty() && memoryEntryIds.isEmpty() && attachmentIds.isEmpty()
                && policies.isEmpty();
        }
        return !blocksAccount(accountId)
            && !denied.contains(new Scope(Kind.ACCOUNT, accountId, NONE, accountId))
            && conversationIds.stream().noneMatch(id -> denied
                .contains(new Scope(Kind.CONVERSATION, accountId, workspaceId, id))
                || conversationReadLocked(accountId, workspaceId, id))
            && resourceIds.stream().noneMatch(id -> denied
                .contains(new Scope(Kind.RESOURCE, accountId, workspaceId, id)))
            && memoryEntryIds.stream().noneMatch(id -> denied
                .contains(new Scope(Kind.MEMORY, accountId, workspaceId, id)))
            && attachmentIds.stream().noneMatch(id -> denied
                .contains(new Scope(Kind.ATTACHMENT, accountId, workspaceId, id)))
            && policies.stream().allMatch(policy -> policy.cutoffs()
                .equals(memoryRevocations(accountId, workspaceId, policy.projectId())));
    }

    record ExportMemoryPolicy(String projectId, MemoryRevocations cutoffs) {
    }

    /**
     * Holds dispatch while restore or a durable deletion is unresolved.
     *
     * @return whether the worker can claim fresh work
     */
    public synchronized boolean dispatchPaused() {
        return enabled && (!writable() || !ready || !pending.isEmpty());
    }

    synchronized Event account(String accountId) {
        return append(Kind.ACCOUNT, accountId, NONE, accountId);
    }

    synchronized Event conversation(String accountId, String workspaceId, String conversationId) {
        return append(Kind.CONVERSATION, accountId, workspaceId, conversationId);
    }

    synchronized Event share(String accountId, String workspaceId, String shareId) {
        return append(Kind.SHARE, accountId, workspaceId, shareId);
    }

    synchronized Event changeSharingRestriction(String accountId, String workspaceId,
        String conversationId, boolean restricted) {
        return append(restricted ? Kind.SHARE_LOCK : Kind.SHARE_UNLOCK, accountId, workspaceId,
            conversationId);
    }

    synchronized Event projectResource(String accountId, String workspaceId, String resourceId) {
        return append(Kind.RESOURCE, accountId, workspaceId, resourceId);
    }

    synchronized Event attachment(String accountId, String workspaceId, String attachmentId) {
        return append(Kind.ATTACHMENT, accountId, workspaceId, attachmentId);
    }

    synchronized Event memoryEntry(String accountId, String workspaceId, String memoryId) {
        return append(Kind.MEMORY, accountId, workspaceId, memoryId);
    }

    synchronized Event memoryOff(String accountId, String workspaceId, String projectId,
        long epoch) {
        if (projectId == null) {
            return append(Kind.MEMORY_OFF, accountId, workspaceId, workspaceId, epoch);
        }
        return append(Kind.PROJECT_MEM_OFF, accountId, workspaceId, projectId, epoch);
    }

    synchronized Event historyOff(String accountId, String workspaceId, long epoch) {
        return append(Kind.HISTORY_OFF, accountId, workspaceId, workspaceId, epoch);
    }

    synchronized Event instructionsOff(String accountId, String workspaceId, long epoch) {
        return append(Kind.INSTRUCTIONS_OFF, accountId, workspaceId, workspaceId, epoch);
    }

    /**
     * Returns the durable cutoff after which an explicit research approval may run.
     *
     * @param accountId research owner
     * @param workspaceId owning workspace
     * @param taskId research task
     * @return revocation epoch, or a closed cutoff while the journal is unavailable
     */
    public synchronized long researchCutoff(String accountId, String workspaceId, String taskId) {
        if (!acceptsErasure() || denied.contains(new Scope(Kind.ACCOUNT, accountId, NONE,
            accountId))) {
            return Long.MAX_VALUE;
        }
        return researchRevocations.getOrDefault(new Scope(Kind.RESEARCH_REVOKE, accountId,
            workspaceId, taskId), 0L);
    }

    synchronized Event revokeResearch(String accountId, String workspaceId, String taskId,
        long epoch) {
        return append(Kind.RESEARCH_REVOKE, accountId, workspaceId, taskId, epoch);
    }

    /**
     * Determines whether a reminder's persisted records may still be used.
     *
     * @param accountId reminder owner
     * @param workspaceId owning workspace
     * @param reminderId reminder identifier
     * @return whether the account or reminder has been revoked
     */
    public synchronized boolean blocksReminder(String accountId, String workspaceId,
        String reminderId) {
        return !acceptsErasure()
            || denied.contains(new Scope(Kind.ACCOUNT, accountId, NONE, accountId))
            || denied.contains(new Scope(Kind.REMINDER_DELETE, accountId, workspaceId, reminderId));
    }

    /**
     * Finds the latest durable pause fence for this schedule.
     *
     * @param accountId reminder owner
     * @param workspaceId owning workspace
     * @param reminderId reminder identifier
     * @return last pause epoch, or a closed cutoff while unavailable
     */
    public synchronized long reminderPauseCutoff(String accountId, String workspaceId,
        String reminderId) {
        return blocksReminder(accountId, workspaceId, reminderId) ? Long.MAX_VALUE
            : reminderPauses.getOrDefault(new Scope(Kind.REMINDER_PAUSE, accountId,
                workspaceId, reminderId), 0L);
    }

    synchronized Event reminderPause(String accountId, String workspaceId, String reminderId,
        long epoch) {
        return append(Kind.REMINDER_PAUSE, accountId, workspaceId, reminderId, epoch);
    }

    synchronized Event reminderDelete(String accountId, String workspaceId, String reminderId) {
        return append(Kind.REMINDER_DELETE, accountId, workspaceId, reminderId);
    }

    /**
     * Holds revoked reminder invitations even while a restored data row is older.
     *
     * @param accountId reminder owner
     * @param workspaceId owning workspace
     * @param reminderId source reminder
     * @param shareId invitation identifier
     * @return whether source or invitation access is blocked
     */
    public synchronized boolean blocksReminderShare(String accountId, String workspaceId,
        String reminderId, String shareId) {
        return blocksReminder(accountId, workspaceId, reminderId)
            || denied.contains(new Scope(Kind.REMINDER_SHARE, accountId, workspaceId, shareId));
    }

    synchronized Event revokeReminderShare(String accountId, String workspaceId, String shareId) {
        return append(Kind.REMINDER_SHARE, accountId, workspaceId, shareId);
    }

    /**
     * Checks whether an invitation or accepted project grant was permanently retired.
     *
     * @param ownerId project owner
     * @param workspaceId owning workspace
     * @param grantId invitation or membership identifier
     * @return whether the grant can no longer authorize access
     */
    public synchronized boolean blocksCollaborationGrant(String ownerId, String workspaceId,
        String grantId) {
        return blocksCollaborationParticipant(ownerId)
            || denied.contains(new Scope(Kind.COLLAB_GRANT, ownerId, workspaceId, grantId));
    }

    /**
     * Checks whether a shared text item is denied across backup restore.
     *
     * @param ownerId project owner
     * @param workspaceId owning workspace
     * @param textId text identifier
     * @return whether the shared text is blocked
     */
    public synchronized boolean blocksCollaborationText(String ownerId, String workspaceId,
        String textId) {
        return blocksCollaborationParticipant(ownerId)
            || denied.contains(new Scope(Kind.COLLAB_TEXT, ownerId, workspaceId, textId));
    }

    /**
     * Checks scoped collaboration participants against pending and applied account erasure.
     *
     * @param accountId participant
     * @return whether the participant is denied
     */
    public synchronized boolean blocksCollaborationParticipant(String accountId) {
        return blocksAccount(accountId)
            || denied.contains(new Scope(Kind.ACCOUNT, accountId, NONE, accountId));
    }

    /**
     * Withholds an organization sign-in consent or session retired by its owner.
     *
     * @param accountId consumer account that owns the credential
     * @param workspaceId exact organization workspace
     * @param objectId exact consent or session identifier
     * @return whether this credential is unavailable
     */
    public synchronized boolean blocksOrganizationSso(String accountId, String workspaceId,
        String objectId) {
        return !acceptsErasure() || blocksCollaborationParticipant(accountId)
            || denied.contains(new Scope(Kind.ORG_SSO_REV, accountId, workspaceId, objectId));
    }

    synchronized Event revokeOrganizationSso(String accountId, String workspaceId,
        String objectId) {
        return append(Kind.ORG_SSO_REV, accountId, workspaceId, objectId);
    }

    /**
     * Returns the latest unlink generation for an exact reviewed directory binding.
     *
     * @param accountId participant in the directory binding
     * @param workspaceId one permitted organization
     * @param bindingId exact immutable membership binding
     * @return the durable fence, or null before the first explicit unlink
     */
    public synchronized String organizationSsoLinkFence(String accountId, String workspaceId,
        String bindingId) {
        return organizationSsoLinkFences
            .get(new Scope(Kind.ORG_SSO_FENCE, accountId, workspaceId, bindingId));
    }

    synchronized Event fenceOrganizationSsoLink(String accountId, String workspaceId,
        String bindingId) {
        return append(Kind.ORG_SSO_FENCE, accountId, workspaceId, bindingId);
    }

    synchronized Event revokeCollaborationGrant(String ownerId, String workspaceId,
        String grantId) {
        return append(Kind.COLLAB_GRANT, ownerId, workspaceId, grantId);
    }

    synchronized Event deleteCollaborationText(String ownerId, String workspaceId, String textId) {
        return append(Kind.COLLAB_TEXT, ownerId, workspaceId, textId);
    }

    /**
     * Determines whether the owner or the selected private assistant was revoked.
     *
     * @param accountId assistant owner
     * @param workspaceId owning workspace
     * @param assistantId assistant identifier
     * @return whether the assistant must remain inaccessible
     */
    public synchronized boolean blocksCustomAssistant(String accountId, String workspaceId,
        String assistantId) {
        return !acceptsErasure()
            || denied.contains(new Scope(Kind.ACCOUNT, accountId, NONE, accountId))
            || denied.contains(new Scope(Kind.CUSTOM_DELETE, accountId, workspaceId, assistantId));
    }

    /**
     * Returns the latest independent cutoff for an older assistant head.
     *
     * @param accountId assistant owner
     * @param workspaceId owning workspace
     * @param assistantId assistant identifier
     * @return last revision cutoff, or a closed cutoff for revoked access
     */
    public synchronized long customAssistantCutoff(String accountId, String workspaceId,
        String assistantId) {
        return blocksCustomAssistant(accountId, workspaceId, assistantId) ? Long.MAX_VALUE
            : customAssistantRevisions.getOrDefault(new Scope(Kind.CUSTOM_REVISE, accountId,
                workspaceId, assistantId), 0L);
    }

    synchronized Event customAssistantRevision(String accountId, String workspaceId,
        String assistantId, long epoch) {
        return append(Kind.CUSTOM_REVISE, accountId, workspaceId, assistantId, epoch);
    }

    synchronized Event customAssistantDelete(String accountId, String workspaceId,
        String assistantId) {
        return append(Kind.CUSTOM_DELETE, accountId, workspaceId, assistantId);
    }

    /**
     * Holds private draft reads after erasure of the artifact, source conversation or account.
     *
     * @param accountId artifact owner
     * @param workspaceId associated workspace
     * @param artifactId artifact identifier
     * @param conversationId optional linked generation source
     * @return whether this draft has been withheld
     */
    public synchronized boolean blocksArtifact(String accountId, String workspaceId,
        String artifactId, String conversationId) {
        return !acceptsErasure()
            || denied.contains(new Scope(Kind.ACCOUNT, accountId, NONE, accountId))
            || denied.contains(new Scope(Kind.ARTIFACT, accountId, workspaceId, artifactId))
            || conversationId != null
                && (denied
                    .contains(new Scope(Kind.CONVERSATION, accountId, workspaceId, conversationId))
                    || conversationReadLocked(accountId, workspaceId, conversationId));
    }

    synchronized Event artifactDelete(String accountId, String workspaceId, String artifactId) {
        return append(Kind.ARTIFACT, accountId, workspaceId, artifactId);
    }

    /**
     * Checks whether a reference to a published artifact version was retired.
     *
     * @param ownerId project owner
     * @param workspaceId owning workspace
     * @param publicationId scoped publication identifier
     * @return whether the publication remains blocked across recovery
     */
    public synchronized boolean blocksArtifactPublication(String ownerId, String workspaceId,
        String publicationId) {
        return blocksCollaborationParticipant(ownerId)
            || denied
                .contains(new Scope(Kind.COLLAB_ARTIFACT, ownerId, workspaceId, publicationId));
    }

    /**
     * Checks the exact linked conversation policy that was authorized for a publication.
     *
     * @param ownerId project owner
     * @param workspaceId owning workspace
     * @param conversationId optional private lineage
     * @param revision exact policy revision observed during publication
     * @return whether the policy blocks this original publication
     */
    public synchronized boolean blocksArtifactPublicationPolicy(String ownerId, String workspaceId,
        String conversationId, String revision) {
        if (conversationId == null) {
            return revision != null;
        }
        Scope scope = new Scope(Kind.SHARE_LOCK, ownerId, workspaceId, conversationId);
        return sharingRestricted(ownerId, workspaceId, conversationId)
            || !Objects.equals(sharingPolicyRevisions.get(scope), revision);
    }

    synchronized Event revokeArtifactPublication(String ownerId, String workspaceId,
        String publicationId) {
        return append(Kind.COLLAB_ARTIFACT, ownerId, workspaceId, publicationId);
    }

    /**
     * Withholds an exact hosted revision independently of the restored database generation.
     *
     * @param ownerId publication owner
     * @param workspaceId owning personal scope
     * @param revision exact active release revision
     * @return whether the revision must remain inaccessible
     */
    public synchronized boolean blocksHostedSite(String ownerId, String workspaceId,
        String revision) {
        return blocksCollaborationParticipant(ownerId)
            || denied.contains(new Scope(Kind.SITE_REV, ownerId, workspaceId, revision));
    }

    synchronized Event retireHostedSite(String ownerId, String workspaceId, String revision) {
        return append(Kind.SITE_REV, ownerId, workspaceId, revision);
    }

    /**
     * Holds one exact catalogue release independently of the recovered database.
     *
     * @param accountId author of the release
     * @param workspaceId submission scope
     * @param releaseId exact release generation
     * @return whether this release must remain inaccessible
     */
    public synchronized boolean blocksCatalogRelease(String accountId, String workspaceId,
        String releaseId) {
        return blocksCollaborationParticipant(accountId)
            || catalogReleaseRetired(accountId, workspaceId, releaseId);
    }

    /**
     * Reports the durable withdrawal fact for a catalogue release status receipt.
     *
     * @param accountId author of the release
     * @param workspaceId submission scope
     * @param releaseId exact release generation
     * @return whether this exact release was durably withdrawn
     */
    public synchronized boolean catalogReleaseRetired(String accountId, String workspaceId,
        String releaseId) {
        return denied.contains(new Scope(Kind.CAT_REV, accountId, workspaceId, releaseId));
    }

    synchronized Event retireCatalogRelease(String accountId, String workspaceId,
        String releaseId) {
        return append(Kind.CAT_REV, accountId, workspaceId, releaseId);
    }

    /**
     * Checks the durable withdrawal of one exact synthetic guardian consent generation.
     *
     * @param participantId enrolled participant
     * @param workspaceId owning fixture workspace
     * @param revision exact previous consent generation
     * @return whether that generation has been retired
     */
    public synchronized boolean guardianConsentRetired(String participantId, String workspaceId,
        String revision) {
        return denied.contains(new Scope(Kind.GUARD_REV, participantId, workspaceId, revision));
    }

    synchronized Event retireGuardianConsent(String participantId, String workspaceId,
        String revision) {
        return append(Kind.GUARD_REV, participantId, workspaceId, revision);
    }

    /**
     * Withholds restored organization policies whose revision has already been superseded.
     *
     * @param ownerId designated organization owner
     * @param workspaceId organization scope
     * @param revision exact reviewed policy revision
     * @return whether this policy must be treated as requiring review
     */
    public synchronized boolean blocksOrganizationPolicy(String ownerId, String workspaceId,
        String revision) {
        return !acceptsErasure() || organizationPolicyRetired(ownerId, workspaceId, revision);
    }

    synchronized boolean organizationPolicyRetired(String ownerId, String workspaceId,
        String revision) {
        return denied.contains(new Scope(Kind.ORG_POLICY, ownerId, workspaceId, revision));
    }

    synchronized Event retireOrganizationPolicy(String ownerId, String workspaceId,
        String revision) {
        return append(Kind.ORG_POLICY, ownerId, workspaceId, revision);
    }

    /**
     * Checks whether a collaborative comment was erased.
     *
     * @param ownerId project owner
     * @param workspaceId owning workspace
     * @param commentId scoped comment identifier
     * @return whether the comment remains blocked after recovery
     */
    public synchronized boolean blocksArtifactComment(String ownerId, String workspaceId,
        String commentId) {
        return blocksCollaborationParticipant(ownerId)
            || denied.contains(new Scope(Kind.COLLAB_COMMENT, ownerId, workspaceId, commentId));
    }

    synchronized Event deleteArtifactComment(String ownerId, String workspaceId, String commentId) {
        return append(Kind.COLLAB_COMMENT, ownerId, workspaceId, commentId);
    }

    /**
     * Checks direct draft retirement and project owner erasure.
     *
     * @param ownerId project owner
     * @param workspaceId owning workspace
     * @param draftId shared draft identifier
     * @return whether the draft remains blocked
     */
    public synchronized boolean blocksSharedDraft(String ownerId, String workspaceId,
        String draftId) {
        return blocksCollaborationParticipant(ownerId)
            || denied.contains(new Scope(Kind.COLLAB_DRAFT, ownerId, workspaceId, draftId));
    }

    synchronized Event deleteSharedDraft(String ownerId, String workspaceId, String draftId) {
        return append(Kind.COLLAB_DRAFT, ownerId, workspaceId, draftId);
    }

    /**
     * Holds a departed generation even while a reader's database snapshot is older.
     *
     * @param accountId workspace member
     * @param workspaceId selected workspace
     * @param revision current database membership generation
     * @return whether that exact membership is withheld
     */
    public synchronized boolean blocksWorkspaceMembership(String accountId, String workspaceId,
        String revision) {
        return enabled && (!acceptsErasure()
            || denied.contains(new Scope(Kind.ACCOUNT, accountId, NONE, accountId))
            || departedWorkspaceMembership(accountId, workspaceId, revision));
    }

    /**
     * Determines whether an earlier exact leave request is already durable.
     *
     * @param accountId workspace member
     * @param workspaceId departed workspace
     * @param revision membership generation supplied by the client
     * @return whether this exact generation was retired
     */
    public synchronized boolean departedWorkspaceMembership(String accountId, String workspaceId,
        String revision) {
        return denied.contains(new Scope(Kind.WORKSPACE_LEAVE, accountId, workspaceId, revision));
    }

    synchronized Event leaveWorkspace(String accountId, String workspaceId, String revision) {
        return append(Kind.WORKSPACE_LEAVE, accountId, workspaceId, revision);
    }

    /**
     * Fences a recovered organization role without retiring the whole workspace membership.
     *
     * @param accountId current member
     * @param workspaceId selected organization
     * @param revision observed authority generation
     * @return whether the recorded administrative authority is unavailable
     */
    public synchronized boolean blocksOrganizationAuthority(String accountId, String workspaceId,
        String revision) {
        return revision == null || !acceptsErasure()
            || denied.contains(new Scope(Kind.ACCOUNT, accountId, NONE, accountId))
            || organizationAuthorityRetired(accountId, workspaceId, revision);
    }

    synchronized boolean organizationAuthorityRetired(String accountId, String workspaceId,
        String revision) {
        return denied.contains(new Scope(Kind.ORG_ROLE, accountId, workspaceId, revision));
    }

    synchronized Event retireOrganizationAuthority(String accountId, String workspaceId,
        String revision) {
        return append(Kind.ORG_ROLE, accountId, workspaceId, revision);
    }

    /**
     * Checks a body reader's exact observed revision against the independent read-lock policy.
     *
     * @param accountId conversation owner
     * @param workspaceId selected workspace
     * @param conversationId source conversation
     * @param revision policy revision observed with the database row
     * @return whether reading is currently blocked
     */
    public synchronized boolean blocksConversationRead(String accountId, String workspaceId,
        String conversationId, String revision) {
        Scope lock = new Scope(Kind.CONV_RD_LOCK, accountId, workspaceId, conversationId);
        return enabled && (!acceptsErasure()
            || denied.contains(new Scope(Kind.ACCOUNT, accountId, NONE, accountId))
            || denied.contains(new Scope(Kind.CONVERSATION, accountId, workspaceId, conversationId))
            || denied.contains(lock)
            || !Objects.equals(conversationReadRevisions.get(lock), revision));
    }

    /**
     * Checks effective read lock for a scoped derived-content path or snapshot guard.
     *
     * @param accountId conversation owner
     * @param workspaceId owning workspace
     * @param conversationId source conversation
     * @return whether the current durable policy is locked
     */
    public synchronized boolean conversationReadLocked(String accountId, String workspaceId,
        String conversationId) {
        return denied
            .contains(new Scope(Kind.CONV_RD_LOCK, accountId, workspaceId, conversationId));
    }

    /**
     * Returns a marker for a currently authorized projection of multiple conversations.
     *
     * @param accountId conversation owner
     * @param workspaceId selected workspace
     * @return most recent read-policy transition for this workspace and owner, or null
     */
    public synchronized String conversationReadFence(String accountId, String workspaceId) {
        return workspaceReadRevisions.get(new Workspace(accountId, workspaceId));
    }

    /**
     * Reads the latest change of placement for a source or target of an explicit history binding.
     *
     * @param accountId conversation owner
     * @param workspaceId selected workspace
     * @param conversationId moved conversation
     * @return placement generation, or null before the first recorded transition
     */
    public synchronized String historyPlacementRevision(String accountId, String workspaceId,
        String conversationId) {
        return historyPlacementRevisions.get(
            new Scope(Kind.CONV_HIST_FENCE, accountId, workspaceId, conversationId));
    }

    synchronized Event fenceHistoryPlacement(String accountId, String workspaceId,
        String conversationId) {
        return append(Kind.CONV_HIST_FENCE, accountId, workspaceId, conversationId);
    }

    synchronized Event changeConversationReadLock(String accountId, String workspaceId,
        String conversationId, boolean locked) {
        return append(locked ? Kind.CONV_RD_LOCK : Kind.CONV_RD_OPEN, accountId, workspaceId,
            conversationId);
    }

    /**
     * Checks whether this exact sandbox authorization is durably closed.
     *
     * @param accountId owner of the grant
     * @param workspaceId grant workspace
     * @param authorizationId immutable authorization identifier
     * @return whether the authorization has been revoked
     */
    public synchronized boolean sandboxAuthorizationRevoked(String accountId, String workspaceId,
        String authorizationId) {
        return denied
            .contains(new Scope(Kind.CONNECTOR_REVOKE, accountId, workspaceId, authorizationId));
    }

    /**
     * Checks whether this scoped operation was irreversibly staged before calling the simulator.
     *
     * @param accountId owner of the action
     * @param workspaceId action workspace
     * @param actionId stable operation id
     * @return whether the action must be verified without dispatching again
     */
    public synchronized boolean sandboxActionStarted(String accountId, String workspaceId,
        String actionId) {
        return denied.contains(new Scope(Kind.CONNECTOR_START, accountId, workspaceId, actionId));
    }

    /**
     * Checks whether an undispatched sandbox operation was withdrawn.
     *
     * @param accountId owner of the action
     * @param workspaceId action workspace
     * @param actionId stable operation id
     * @return whether the operation is durably cancelled
     */
    public synchronized boolean sandboxActionCancelled(String accountId, String workspaceId,
        String actionId) {
        return denied.contains(new Scope(Kind.CONNECTOR_CANCEL, accountId, workspaceId, actionId));
    }

    synchronized Event sandboxConnectorEvent(Kind kind, String accountId, String workspaceId,
        String targetId) {
        return switch (kind) {
            case CONNECTOR_REVOKE, CONNECTOR_CANCEL, CONNECTOR_START ->
                append(kind, accountId, workspaceId, targetId);
            default -> throw new IllegalArgumentException("Invalid sandbox connector event.");
        };
    }

    /**
     * Checks durable deletion of an owner-scoped event reminder.
     *
     * @param accountId owner account
     * @param workspaceId selected workspace
     * @param id event reminder
     * @return whether the reminder is unavailable
     */
    public synchronized boolean sandboxEventReminderDeleted(String accountId, String workspaceId,
        String id) {
        return !acceptsErasure()
            || denied.contains(new Scope(Kind.EV_REM_DELETE, accountId, workspaceId, id));
    }

    /**
     * Reads the latest revoked scheduling generation for an event reminder.
     *
     * @param accountId owner account
     * @param workspaceId selected workspace
     * @param id event reminder
     * @return revoked cutoff, or zero for a new reminder
     */
    public synchronized long sandboxEventPauseCutoff(String accountId, String workspaceId,
        String id) {
        return acceptsErasure()
            ? reminderPauses.getOrDefault(new Scope(Kind.EV_REM_PAUSE, accountId,
                workspaceId, id), 0L)
            : Long.MAX_VALUE;
    }

    synchronized Event sandboxEventReminderDelete(String accountId, String workspaceId, String id) {
        return append(Kind.EV_REM_DELETE, accountId, workspaceId, id);
    }

    synchronized Event sandboxEventReminderPause(String accountId, String workspaceId, String id,
        long epoch) {
        return append(Kind.EV_REM_PAUSE, accountId, workspaceId, id, epoch);
    }

    /**
     * Checks the independent off or policy-replacement generation for local suggestions.
     *
     * @param accountId owner account
     * @param workspaceId selected workspace
     * @return invalidated policy cutoff
     */
    public synchronized long proactivePreferenceCutoff(String accountId, String workspaceId) {
        return acceptsErasure() ? memoryRevocations.getOrDefault(
            new Scope(Kind.PR_PREF_OFF, accountId, workspaceId, workspaceId), 0L) : Long.MAX_VALUE;
    }

    /**
     * Checks whether the owner has permanently dismissed this suggestion.
     *
     * @param accountId owner account
     * @param workspaceId selected workspace
     * @param id suggestion identifier
     * @return whether this suggestion must be hidden
     */
    public synchronized boolean proactiveSuggestionDismissed(String accountId, String workspaceId,
        String id) {
        return !acceptsErasure()
            || denied.contains(new Scope(Kind.PR_SUG_DISMISS, accountId, workspaceId, id));
    }

    /**
     * Checks whether the same daily suggestion was already issued in this scope.
     *
     * @param accountId owner account
     * @param workspaceId selected workspace
     * @param id stable role and local day identifier
     * @return whether issuance has already been recorded
     */
    public synchronized boolean proactiveSuggestionIssued(String accountId, String workspaceId,
        String id) {
        return !acceptsErasure()
            || denied.contains(new Scope(Kind.PR_SUG_ISSUED, accountId, workspaceId, id));
    }

    synchronized Event proactivePreferenceOff(String accountId, String workspaceId, long epoch) {
        return append(Kind.PR_PREF_OFF, accountId, workspaceId, workspaceId, epoch);
    }

    synchronized Event proactiveSuggestionDismiss(String accountId, String workspaceId, String id) {
        return append(Kind.PR_SUG_DISMISS, accountId, workspaceId, id);
    }

    synchronized Event proactiveSuggestionIssue(String accountId, String workspaceId, String id) {
        return append(Kind.PR_SUG_ISSUED, accountId, workspaceId, id);
    }

    synchronized List<Event> pending() {
        return writable() ? List.copyOf(pending.values()) : List.of();
    }

    synchronized void applied(Event event) {
        pending.remove(event.id());
    }

    synchronized void verify(Collection<String> previouslyApplied) {
        if (!writable() || !entries.keySet().containsAll(previouslyApplied)) {
            throw new IllegalStateException(
                "Privacy deletion journal cannot verify restored state.");
        }
    }

    synchronized void ready() {
        if (!writable() || !pending.isEmpty()) {
            throw new IllegalStateException("Privacy deletion replay did not finish.");
        }
        ready = true;
    }

    /**
     * Releases the single-writer lock and file.
     */
    @PreDestroy
    public synchronized void close() {
        healthy = false;
        if (channel != null) {
            try {
                channel.close();
            } catch (IOException ignored) {
                // Closing the channel also releases the operating-system lock.
            }
            channel = null;
        }
    }

    private void open(String path, String id) {
        try {
            Path journal = Path.of(path);
            if (!journal.isAbsolute() || !isUuid(id)
                || !Files.isRegularFile(journal, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Journal must be a provisioned file.");
            }
            channel = FileChannel.open(journal, StandardOpenOption.READ, StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS);
            writerLock = channel.tryLock();
            if (writerLock == null || channel.size() > MAX_BYTES) {
                throw new IOException("Journal cannot be opened exclusively.");
            }
            ByteBuffer bytes = ByteBuffer.allocate((int) channel.size());
            while (bytes.hasRemaining()) {
                if (channel.read(bytes) < 0) {
                    throw new IOException("Journal ended unexpectedly.");
                }
            }
            parse(new String(bytes.array(), StandardCharsets.US_ASCII), HEADER + id);
            healthy = true;
        } catch (IOException | RuntimeException failure) {
            close();
            throw new IllegalStateException("Privacy deletion journal is unavailable or invalid.");
        }
    }

    private void parse(String contents, String header) {
        String[] lines = contents.split("\\n", -1);
        if (lines.length < 2 || !header.equals(lines[0]) || !lines[lines.length - 1].isEmpty()) {
            throw new IllegalArgumentException("Invalid journal header or incomplete entry.");
        }
        tip = digest(header);
        for (int index = 1; index < lines.length - 1; index++) {
            String line = lines[index];
            String[] fields = line.split(":", -1);
            if (fields.length != 7 || !tip.equals(fields[5])) {
                throw new IllegalArgumentException("Invalid journal entry sequence.");
            }
            Kind kind = Kind.valueOf(fields[0]);
            validateScope(kind, fields[1], fields[2], fields[3]);
            long time = Long.parseLong(fields[4]);
            String reference = digest(line.substring(0, line.lastIndexOf(':')));
            if (time <= 0 || !reference.equals(fields[6]) || entries.containsKey(reference)) {
                throw new IllegalArgumentException("Invalid journal entry checksum.");
            }
            var event = new Event(reference, kind, fields[1], fields[2], fields[3], time);
            retain(event);
        }
    }

    private Event append(Kind kind, String accountId, String workspaceId, String targetId) {
        return append(kind, accountId, workspaceId, targetId, 0L);
    }

    private Event append(Kind kind, String accountId, String workspaceId, String targetId,
        long epoch) {
        if (!enabled) {
            throw new AssistantApiException(HttpStatus.FORBIDDEN, "CAPABILITY_UNAVAILABLE",
                "Account erasure is unavailable.");
        }
        if (!writable() || !ready) {
            throw unavailable();
        }
        validateScope(kind, accountId, workspaceId, targetId);
        long now = Math.max(System.currentTimeMillis(), epoch);
        String value =
            kind + ":" + accountId + ":" + workspaceId + ":" + targetId + ":" + now + ":" + tip;
        String id = digest(value);
        byte[] line = (value + ":" + id + "\n").getBytes(StandardCharsets.US_ASCII);
        try {
            if (line.length > MAX_BYTES - channel.size()) {
                throw new IOException("Journal is full.");
            }
            ByteBuffer bytes = ByteBuffer.wrap(line);
            channel.position(channel.size());
            while (bytes.hasRemaining()) {
                channel.write(bytes);
            }
            channel.force(true);
            var event = new Event(id, kind, accountId, workspaceId, targetId, now);
            retain(event);
            return event;
        } catch (IOException | RuntimeException failure) {
            healthy = false;
            throw unavailable();
        }
    }

    private void retain(Event event) {
        entries.put(event.id(), event);
        pending.put(event.id(), event);
        Scope scope =
            new Scope(event.kind(), event.accountId(), event.workspaceId(), event.targetId());
        switch (event.kind()) {
            case MEMORY_OFF, PROJECT_MEM_OFF, HISTORY_OFF, INSTRUCTIONS_OFF, PR_PREF_OFF ->
                memoryRevocations.merge(scope, event.createdAt(), Math::max);
            case RESEARCH_REVOKE -> researchRevocations.merge(scope, event.createdAt(), Math::max);
            case REMINDER_PAUSE, EV_REM_PAUSE ->
                reminderPauses.merge(scope, event.createdAt(), Math::max);
            case CUSTOM_REVISE ->
                customAssistantRevisions.merge(scope, event.createdAt(), Math::max);
            case SHARE_LOCK -> {
                denied.add(scope);
                sharingPolicyRevisions.put(scope, event.id());
            }
            case SHARE_UNLOCK -> {
                Scope restriction = new Scope(Kind.SHARE_LOCK, event.accountId(),
                    event.workspaceId(), event.targetId());
                denied.remove(restriction);
                sharingPolicyRevisions.put(restriction, event.id());
            }
            case CONV_RD_LOCK, CONV_RD_OPEN -> retainConversationPolicy(event);
            case CONV_HIST_FENCE -> historyPlacementRevisions.put(scope, event.id());
            case ORG_SSO_FENCE -> organizationSsoLinkFences.put(scope, event.id());
            default -> denied.add(scope);
        }
        tip = event.id();
    }

    private void retainConversationPolicy(Event event) {
        Scope scope = new Scope(Kind.CONV_RD_LOCK, event.accountId(), event.workspaceId(),
            event.targetId());
        if (event.kind() == Kind.CONV_RD_LOCK) {
            denied.add(scope);
        } else {
            denied.remove(scope);
        }
        conversationReadRevisions.put(scope, event.id());
        workspaceReadRevisions.put(new Workspace(event.accountId(), event.workspaceId()),
            event.id());
    }

    private boolean writable() {
        return healthy && writerLock != null && writerLock.isValid();
    }

    private static void validateScope(Kind kind, String accountId, String workspaceId,
        String targetId) {
        boolean valid = isUuid(accountId) && isUuid(targetId)
            && (kind == Kind.ACCOUNT ? NONE.equals(workspaceId) && accountId.equals(targetId)
                : isUuid(workspaceId));
        boolean workspaceControl = kind == Kind.MEMORY_OFF || kind == Kind.HISTORY_OFF
            || kind == Kind.INSTRUCTIONS_OFF || kind == Kind.PR_PREF_OFF;
        if (!valid || workspaceControl && !workspaceId.equals(targetId)) {
            throw new IllegalArgumentException("Invalid deletion scope.");
        }
    }

    private static boolean isUuid(String input) {
        return input.length() == 36 && UUID.fromString(input).toString().equals(input);
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("Journal checksum unavailable.", failure);
        }
    }

    private static AssistantApiException unavailable() {
        return new AssistantApiException(HttpStatus.SERVICE_UNAVAILABLE, "CAPABILITY_UNAVAILABLE",
            "The privacy deletion journal is unavailable.");
    }

    enum Kind {
        ACCOUNT,
        CONVERSATION,
        SHARE,
        RESOURCE,
        MEMORY,
        MEMORY_OFF,
        PROJECT_MEM_OFF,
        HISTORY_OFF,
        INSTRUCTIONS_OFF,
        SHARE_LOCK,
        SHARE_UNLOCK,
        ATTACHMENT,
        RESEARCH_REVOKE,
        REMINDER_DELETE,
        REMINDER_PAUSE,
        CUSTOM_DELETE,
        CUSTOM_REVISE,
        REMINDER_SHARE,
        COLLAB_GRANT,
        COLLAB_TEXT,
        ARTIFACT,
        COLLAB_ARTIFACT,
        COLLAB_COMMENT,
        COLLAB_DRAFT,
        WORKSPACE_LEAVE,
        ORG_ROLE,
        CONV_RD_LOCK,
        CONV_RD_OPEN,
        CONV_HIST_FENCE,
        CONNECTOR_REVOKE,
        CONNECTOR_CANCEL,
        CONNECTOR_START,
        EV_REM_DELETE,
        EV_REM_PAUSE,
        PR_PREF_OFF,
        PR_SUG_DISMISS,
        PR_SUG_ISSUED,
        ORG_POLICY,
        SITE_REV,
        CAT_REV,
        GUARD_REV,
        ORG_SSO_REV,
        ORG_SSO_FENCE
    }

    private record Scope(Kind kind, String accountId, String workspaceId, String targetId) {
    }

    private record Workspace(String accountId, String workspaceId) {
    }

    record Event(String id, Kind kind, String accountId, String workspaceId, String targetId,
        long createdAt) {
    }

    /**
     * Latest durable cutoff for each independently editable memory control.
     *
     * @param memory last disable epoch in the selected scope
     * @param history last personal history-reference disable epoch
     * @param instructions last personal instruction removal epoch
     */
    public record MemoryRevocations(long memory, long history, long instructions) {
    }
}

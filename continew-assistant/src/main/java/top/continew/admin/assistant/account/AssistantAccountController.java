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

import cn.dev33.satoken.annotation.SaIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.feiniaojin.gracefulresponse.api.ExcludeFromGracefulResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import top.continew.admin.assistant.artifact.AssistantArtifacts;
import top.continew.admin.assistant.artifact.AssistantHostedSites;
import top.continew.admin.assistant.attachment.AssistantAttachments;
import top.continew.admin.assistant.catalog.AssistantCatalog;
import top.continew.admin.assistant.chat.AssistantConversationReadLock;
import top.continew.admin.assistant.chat.AssistantTemporaryChat;
import top.continew.admin.assistant.commerce.AssistantSyntheticCatalogue;
import top.continew.admin.assistant.companion.AssistantDesktopCompanion;
import top.continew.admin.assistant.connector.AssistantSandboxConnector;
import top.continew.admin.assistant.eligibility.AssistantEligibility;
import top.continew.admin.assistant.model.AssistantModels;
import top.continew.admin.assistant.organization.AssistantOrganizationDirectory;
import top.continew.admin.assistant.organization.AssistantOrganizationModelBudgets;
import top.continew.admin.assistant.organization.AssistantOrganizationPolicies;
import top.continew.admin.assistant.organization.AssistantOrganizations;
import top.continew.admin.assistant.privacy.AssistantDeletionJournal;
import top.continew.admin.assistant.project.AssistantProjectArtifactPublications;
import top.continew.admin.assistant.reminder.AssistantEventReminders;
import top.continew.admin.assistant.reminder.AssistantProactiveAssistant;
import top.continew.admin.assistant.run.AssistantHistoryReferences;
import top.continew.starter.extension.tenant.annotation.TenantIgnore;
import top.continew.starter.log.annotation.Log;
import top.continew.starter.ratelimiter.annotation.RateLimiter;
import top.continew.starter.ratelimiter.exception.RateLimiterException;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Consumer authentication endpoints, separate from ContiNew administration.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
@RestController
@RequestMapping("/api/assistant/v1")
@ConditionalOnProperty(name = "assistant.enabled", havingValue = "true")
@ExcludeFromGracefulResponse
@SaIgnore
@TenantIgnore
@Log(ignore = true)
public class AssistantAccountController {

    private static final String WORKSPACE_HEADER = "X-Workspace-Id";
    private final AssistantAccounts accounts;
    private final AssistantModels models;
    private final AssistantTemporaryChat temporaryChat;
    private final AssistantAttachments attachments;
    private final AssistantArtifacts artifacts;
    private final AssistantHostedSites hostedSites;
    private final AssistantCatalog catalog;
    private final AssistantDeletionJournal deletionJournal;
    private final AssistantConversationReadLock readLock;
    private final AssistantHistoryReferences historyReferences;
    private final AssistantProjectArtifactPublications publications;
    private final AssistantSandboxConnector sandboxConnector;
    private final AssistantEventReminders eventReminders;
    private final AssistantSyntheticCatalogue syntheticCatalogue;
    private final AssistantOrganizations organizations;
    private final AssistantOrganizationPolicies organizationPolicies;
    private final AssistantProactiveAssistant proactiveAssistant;
    private final AssistantDesktopCompanion desktopCompanion;
    private final AssistantEligibility eligibility;
    private final AssistantOrganizationModelBudgets organizationModelBudgets;
    private final AssistantOrganizationDirectory organizationDirectory;
    private final boolean jobsEnabled;
    private final boolean sharesEnabled;
    private final boolean retrievalEnabled;
    private final boolean projectResourcesEnabled;
    private final boolean numericAnalysisEnabled;
    private final boolean memoryEnabled;
    private final boolean researchEnabled;
    private final boolean remindersEnabled;
    private final boolean reminderTemplatesEnabled;
    private final boolean customAssistantsEnabled;
    private final boolean projectCollaborationEnabled;
    private final boolean artifactsEnabled;
    private final boolean projectArtifactPublicationsEnabled;
    private final boolean spreadsheetsEnabled;
    private final boolean projectArtifactCommentsEnabled;
    private final boolean projectSharedDraftsEnabled;
    private final boolean slidesEnabled;
    private final boolean codeDraftsEnabled;
    private final boolean workspaceDepartureEnabled;
    private final boolean webpageDraftsEnabled;
    private final boolean webpagePublicationsEnabled;

    /**
     * Creates the account controller.
     *
     * @param accounts consumer account service
     * @param models available generation models
     * @param temporaryChat isolated synthetic chat service
     * @param attachments private binary attachment service
     * @param artifacts versioned drafts and approved image assets
     * @param hostedSites explicitly reviewed personal project webpage releases
     * @param catalog reviewed assistants and first-party connector descriptions
     * @param deletionJournal recoverable account deletion status
     * @param readLock private conversation reading protection and password recovery
     * @param historyReferences explicit reuse of an earlier completed local reply
     * @param publications reviewed project publications
     * @param sandboxConnector opt-in local simulator for approved automation actions
     * @param eventReminders reminders from authorized connector receipts
     * @param syntheticCatalogue scoped synthetic product catalogue
     * @param organizations centrally provisioned consumer workspaces
     * @param organizationPolicies reviewed model and connector selection controls
     * @param proactiveAssistant optional local calendar suggestions
     * @param desktopCompanion optional local same-owner desktop review
     * @param eligibility scoped local guardian eligibility pilot
     * @param organizationModelBudgets independent organization model output budgets
     * @param organizationDirectory directory enrollment for an exact organization membership
     * @param jobsEnabled background tasks feature flag
     * @param sharesEnabled conversation sharing feature flag
     * @param retrievalEnabled evidence retrieval feature flag
     * @param projectResourcesEnabled private text materials feature flag
     * @param numericAnalysisEnabled built-in CSV analysis feature flag
     * @param memoryEnabled manual memory feature flag
     * @param researchEnabled curated research feature flag
     * @param remindersEnabled scheduled reminders feature flag
     * @param reminderTemplatesEnabled authenticated calendar share feature flag
     * @param customAssistantsEnabled private custom assistant registry feature flag
     * @param projectCollaborationEnabled scoped shared project text feature flag
     * @param artifactsEnabled private versioned artifact feature flag
     * @param projectArtifactPublicationsEnabled explicit project document publication feature flag
     * @param spreadsheetsEnabled private spreadsheet create and edit feature flag
     * @param projectArtifactCommentsEnabled published project version comment feature flag
     * @param projectSharedDraftsEnabled explicitly shared Markdown draft feature flag
     * @param slidesEnabled private presentation create and edit feature flag
     * @param codeDraftsEnabled private source-code draft create and edit feature flag
     * @param workspaceDepartureEnabled durable self-service workspace membership departure flag
     * @param webpageDraftsEnabled private static webpage create and edit feature flag
     * @param webpagePublicationsEnabled explicitly published private project webpages feature flag
     */
    public AssistantAccountController(AssistantAccounts accounts, AssistantModels models,
        AssistantTemporaryChat temporaryChat, AssistantAttachments attachments,
        AssistantArtifacts artifacts, AssistantHostedSites hostedSites, AssistantCatalog catalog,
        AssistantDeletionJournal deletionJournal, AssistantConversationReadLock readLock,
        AssistantHistoryReferences historyReferences,
        AssistantProjectArtifactPublications publications,
        AssistantSandboxConnector sandboxConnector,
        AssistantEventReminders eventReminders,
        AssistantSyntheticCatalogue syntheticCatalogue,
        AssistantOrganizations organizations, AssistantOrganizationPolicies organizationPolicies,
        AssistantProactiveAssistant proactiveAssistant, AssistantDesktopCompanion desktopCompanion,
        AssistantEligibility eligibility,
        AssistantOrganizationModelBudgets organizationModelBudgets,
        AssistantOrganizationDirectory organizationDirectory,
        @Value("${assistant.jobs.enabled:false}") boolean jobsEnabled,
        @Value("${assistant.shares.enabled:false}") boolean sharesEnabled,
        @Value("${assistant.retrieval.enabled:false}") boolean retrievalEnabled,
        @Value("${assistant.project-resources.enabled:false}") boolean projectResourcesEnabled,
        @Value("${assistant.analysis.enabled:false}") boolean numericAnalysisEnabled,
        @Value("${assistant.memory.enabled:false}") boolean memoryEnabled,
        @Value("${assistant.research.enabled:false}") boolean researchEnabled,
        @Value("${assistant.reminders.enabled:false}") boolean remindersEnabled,
        @Value("${assistant.reminder-templates.enabled:false}") boolean reminderTemplatesEnabled,
        @Value("${assistant.custom-assistants.enabled:false}") boolean customAssistantsEnabled,
        @Value("${assistant.project-collaboration.enabled:false}") boolean projectCollaborationEnabled,
        @Value("${assistant.artifacts.enabled:false}") boolean artifactsEnabled,
        @Value("${assistant.project-artifact-publications.enabled:false}") boolean projectArtifactPublicationsEnabled,
        @Value("${assistant.artifacts.spreadsheets.enabled:false}") boolean spreadsheetsEnabled,
        @Value("${assistant.project-artifact-comments.enabled:false}") boolean projectArtifactCommentsEnabled,
        @Value("${assistant.project-shared-drafts.enabled:false}") boolean projectSharedDraftsEnabled,
        @Value("${assistant.artifacts.slides.enabled:false}") boolean slidesEnabled,
        @Value("${assistant.artifacts.code.enabled:false}") boolean codeDraftsEnabled,
        @Value("${assistant.workspace-departure.enabled:false}") boolean workspaceDepartureEnabled,
        @Value("${assistant.artifacts.webpages.enabled:false}") boolean webpageDraftsEnabled,
        @Value("${assistant.project-webpage-publications.enabled:false}") boolean webpagePublicationsEnabled) {
        this.accounts = accounts;
        this.models = models;
        this.temporaryChat = temporaryChat;
        this.attachments = attachments;
        this.artifacts = artifacts;
        this.hostedSites = hostedSites;
        this.catalog = catalog;
        this.deletionJournal = deletionJournal;
        this.readLock = readLock;
        this.historyReferences = historyReferences;
        this.publications = publications;
        this.sandboxConnector = sandboxConnector;
        this.eventReminders = eventReminders;
        this.syntheticCatalogue = syntheticCatalogue;
        this.organizations = organizations;
        this.organizationPolicies = organizationPolicies;
        this.proactiveAssistant = proactiveAssistant;
        this.desktopCompanion = desktopCompanion;
        this.eligibility = eligibility;
        this.organizationModelBudgets = organizationModelBudgets;
        this.organizationDirectory = organizationDirectory;
        this.jobsEnabled = jobsEnabled;
        this.sharesEnabled = sharesEnabled;
        this.retrievalEnabled = retrievalEnabled;
        this.projectResourcesEnabled = projectResourcesEnabled;
        this.numericAnalysisEnabled = numericAnalysisEnabled;
        this.memoryEnabled = memoryEnabled;
        this.researchEnabled = researchEnabled;
        this.remindersEnabled = remindersEnabled;
        this.reminderTemplatesEnabled = reminderTemplatesEnabled;
        this.customAssistantsEnabled = customAssistantsEnabled;
        this.projectCollaborationEnabled = projectCollaborationEnabled;
        this.artifactsEnabled = artifactsEnabled;
        this.projectArtifactPublicationsEnabled = projectArtifactPublicationsEnabled;
        this.spreadsheetsEnabled = spreadsheetsEnabled;
        this.projectArtifactCommentsEnabled = projectArtifactCommentsEnabled;
        this.projectSharedDraftsEnabled = projectSharedDraftsEnabled;
        this.slidesEnabled = slidesEnabled;
        this.codeDraftsEnabled = codeDraftsEnabled;
        this.workspaceDepartureEnabled = workspaceDepartureEnabled;
        this.webpageDraftsEnabled = webpageDraftsEnabled;
        this.webpagePublicationsEnabled = webpagePublicationsEnabled;
    }

    /**
     * Registers a consumer account when registrations are enabled.
     *
     * @param credentials new account credentials
     * @param request incoming request used to get the transport peer
     * @return new session
     */
    @PostMapping("/auth/register")
    @RateLimiter(name = "assistant.register", key = "#root.args[1].remoteAddr", rate = 8,
        interval = 1,
        unit = TimeUnit.MINUTES)
    public ResponseEntity<AssistantAccounts.Session> register(
        @RequestBody AssistantAccounts.Credentials credentials, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
            .body(accounts.register(credentials));
    }

    /**
     * Starts a new device session.
     *
     * @param credentials consumer credentials
     * @param request incoming request used to get the transport peer
     * @return new session
     */
    @PostMapping("/auth/login")
    @RateLimiter(name = "assistant.login", key = "#root.args[1].remoteAddr", rate = 12,
        interval = 1,
        unit = TimeUnit.MINUTES)
    public ResponseEntity<AssistantAccounts.Session> login(
        @RequestBody AssistantAccounts.Credentials credentials, HttpServletRequest request) {
        return ok(accounts.login(credentials));
    }

    /**
     * Returns the authenticated session's consumer profile.
     *
     * @param authorization consumer bearer header
     * @param workspace requested workspace or null
     * @return active account
     */
    @GetMapping("/auth/me")
    public ResponseEntity<AssistantAccounts.User> me(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE_HEADER, required = false) String workspace) {
        return ok(accounts.authenticate(authorization, workspace).user());
    }

    /**
     * Lists active memberships without relying on the caller's current workspace.
     *
     * @param authorization consumer bearer header
     * @param cursor prior page continuation
     * @param limit page size
     * @return the workspace membership page
     */
    @GetMapping("/auth/workspaces")
    public ResponseEntity<AssistantAccounts.WorkspacePage> workspaces(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestParam(value = "cursor", required = false) String cursor,
        @RequestParam(value = "limit", defaultValue = "25") int limit) {
        return ok(accounts.workspaces(accounts.authenticate(authorization, null), cursor, limit));
    }

    /**
     * Updates profile preferences for the active account.
     *
     * @param authorization consumer bearer header
     * @param workspace requested workspace or null
     * @param preferences profile updates
     * @return updated account
     */
    @PatchMapping("/auth/me/preferences")
    public ResponseEntity<AssistantAccounts.User> updatePreferences(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE_HEADER, required = false) String workspace,
        @RequestBody AssistantAccounts.Preferences preferences) {
        var principal = accounts.authenticate(authorization, workspace);
        return ok(accounts.updatePreferences(principal, preferences));
    }

    /**
     * Lists the active account's device sessions.
     *
     * @param authorization consumer bearer header
     * @param workspace requested workspace or null
     * @return device list
     */
    @GetMapping("/auth/devices")
    public ResponseEntity<List<AssistantAccounts.Device>> devices(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE_HEADER, required = false) String workspace) {
        return ok(accounts.devices(accounts.authenticate(authorization, workspace)));
    }

    /**
     * Revokes a device session owned by the active account.
     *
     * @param authorization consumer bearer header
     * @param workspace requested workspace or null
     * @param id device session ID
     * @return empty response
     */
    @DeleteMapping("/auth/devices/{id}")
    public ResponseEntity<Void> revoke(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE_HEADER, required = false) String workspace,
        @PathVariable("id") String id) {
        accounts.revoke(accounts.authenticate(authorization, workspace), id);
        return noContent();
    }

    /**
     * Revokes the active device session.
     *
     * @param authorization consumer bearer header
     * @param workspace requested workspace or null
     * @return empty response
     */
    @PostMapping("/auth/logout")
    public ResponseEntity<Void> logout(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE_HEADER, required = false) String workspace) {
        var principal = accounts.authenticate(authorization, workspace);
        accounts.revoke(principal, principal.sessionId());
        return noContent();
    }

    /**
     * Allows guests and consumers to discover the available features.
     *
     * @param authorization consumer bearer header
     * @param workspace requested workspace or null
     * @return feature availability
     */
    @GetMapping("/capabilities")
    public ResponseEntity<Capabilities> capabilities(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE_HEADER, required = false) String workspace) {
        boolean authenticated = authorization != null;
        AssistantAccounts.User user =
            authenticated ? accounts.authenticate(authorization, workspace).user() : null;
        var deploymentModels = models.list();
        var policy =
            authenticated ? organizationPolicies.effective(user.workspaceId(), false) : null;
        var workspaceModels = policy == null ? List.<AssistantModels.Model>of()
            : deploymentModels.stream().filter(model -> policy.allowsModel(model.id())).toList();
        boolean textAvailable = !workspaceModels.isEmpty();
        boolean temporaryReady = temporaryChat.available() && (policy == null
            || policy.allowsModel(AssistantTemporaryChat.MODEL_ID)
                && organizationModelBudgets.temporaryAllowed(user));
        String textUnavailable = !authenticated ? "SIGN_IN_REQUIRED"
            : deploymentModels.isEmpty() ? "PROVIDER_UNAVAILABLE" : "ORGANIZATION_POLICY_DENIED";
        boolean erasureReady = deletionJournal.acceptsErasure();
        String erasureUnavailable = !deletionJournal.enabled() ? "POLICY_DISABLED"
            : erasureReady ? "SIGN_IN_REQUIRED" : "PROVIDER_UNAVAILABLE";
        boolean shareReady = sharesEnabled && erasureReady;
        String shareUnavailable = sharesEnabled ? erasureUnavailable : "POLICY_DISABLED";
        String retrievalUnavailable = retrievalEnabled ? "SIGN_IN_REQUIRED" : "POLICY_DISABLED";
        boolean projectResourcesReady = projectResourcesEnabled && erasureReady;
        String projectResourcesUnavailable = projectResourcesEnabled ? erasureUnavailable
            : "POLICY_DISABLED";
        boolean analysisReady = numericAnalysisEnabled && projectResourcesReady;
        boolean attachmentsReady = attachments.available();
        boolean localImageEditsReady = attachments.localImageEditsAvailable();
        boolean audioUploadsReady = attachments.audioUploadAvailable();
        boolean audioManagementReady = authenticated && attachments.audioManagementAvailable(user);
        boolean pdfUploadsReady = attachments.pdfUploadAvailable();
        boolean pdfManagementReady = authenticated && attachments.pdfManagementAvailable(user);
        String analysisUnavailable = numericAnalysisEnabled ? projectResourcesUnavailable
            : "POLICY_DISABLED";
        boolean memoryReady = memoryEnabled && erasureReady;
        String memoryUnavailable = memoryEnabled ? erasureUnavailable : "POLICY_DISABLED";
        boolean researchReady = researchEnabled && retrievalEnabled && erasureReady;
        String researchUnavailable = researchEnabled && retrievalEnabled ? erasureUnavailable
            : "POLICY_DISABLED";
        boolean remindersReady = remindersEnabled && erasureReady;
        String remindersUnavailable = remindersEnabled ? erasureUnavailable : "POLICY_DISABLED";
        boolean reminderTemplatesReady = reminderTemplatesEnabled && remindersReady;
        String reminderTemplatesUnavailable =
            reminderTemplatesEnabled ? remindersUnavailable : "POLICY_DISABLED";
        boolean customAssistantsReady = customAssistantsEnabled && erasureReady;
        String customAssistantsUnavailable =
            customAssistantsEnabled ? erasureUnavailable : "POLICY_DISABLED";
        boolean customRunsReady = customAssistantsReady
            && workspaceModels.stream().anyMatch(models::supportsLocalMemory);
        String customRunsUnavailable =
            customAssistantsReady ? "POLICY_DISABLED" : customAssistantsUnavailable;
        boolean projectCollaborationReady = projectCollaborationEnabled && erasureReady;
        String projectCollaborationUnavailable =
            projectCollaborationEnabled ? erasureUnavailable : "POLICY_DISABLED";
        boolean artifactsReady = artifactsEnabled && erasureReady;
        String artifactsUnavailable = artifactsEnabled ? erasureUnavailable : "POLICY_DISABLED";
        boolean publicationPolicy =
            projectArtifactPublicationsEnabled && projectCollaborationEnabled && artifactsEnabled;
        boolean projectArtifactPublicationsReady = publicationPolicy && erasureReady;
        String projectArtifactPublicationsUnavailable =
            publicationPolicy ? erasureUnavailable : "POLICY_DISABLED";
        boolean spreadsheetsReady = spreadsheetsEnabled && artifactsReady;
        String spreadsheetsUnavailable =
            spreadsheetsEnabled ? artifactsUnavailable : "POLICY_DISABLED";
        boolean projectArtifactCommentsReady =
            projectArtifactCommentsEnabled && projectArtifactPublicationsReady;
        String projectArtifactCommentsUnavailable =
            projectArtifactCommentsEnabled ? projectArtifactPublicationsUnavailable
                : "POLICY_DISABLED";
        boolean projectSharedDraftsReady = projectSharedDraftsEnabled && projectCollaborationReady;
        String projectSharedDraftsUnavailable =
            projectSharedDraftsEnabled ? projectCollaborationUnavailable : "POLICY_DISABLED";
        boolean slidesReady = slidesEnabled && artifactsReady;
        String slidesUnavailable = slidesEnabled ? artifactsUnavailable : "POLICY_DISABLED";
        boolean codeReady = codeDraftsEnabled && artifactsReady;
        String codeUnavailable = codeDraftsEnabled ? artifactsUnavailable : "POLICY_DISABLED";
        boolean workspaceDepartureReady = workspaceDepartureEnabled && erasureReady;
        String workspaceDepartureUnavailable =
            workspaceDepartureEnabled ? erasureUnavailable : "POLICY_DISABLED";
        boolean webpagesReady = webpageDraftsEnabled && artifactsReady;
        String webpagesUnavailable =
            webpageDraftsEnabled ? artifactsUnavailable : "POLICY_DISABLED";
        boolean webpagePublicationsReady =
            webpagePublicationsEnabled && projectArtifactPublicationsReady;
        String webpagePublicationsUnavailable =
            webpagePublicationsEnabled ? projectArtifactPublicationsUnavailable : "POLICY_DISABLED";
        boolean readingLockReady = readLock.creationAvailable();
        boolean lockedCatalogueReady = authenticated && readLock.catalogueAvailable(user);
        boolean imageAssetsReady = artifacts.imageEditingAvailable();
        boolean historyReferencesReady = authenticated && historyReferences.available(user);
        boolean imagePublicationsReady = projectArtifactPublicationsReady
            && publications.imagePublishingAvailable();
        boolean spreadsheetPublicationsReady = projectArtifactPublicationsReady
            && publications.spreadsheetPublishingAvailable();
        boolean slidePublicationsReady = projectArtifactPublicationsReady
            && publications.slidePublishingAvailable();
        boolean codePublicationsReady = projectArtifactPublicationsReady
            && publications.codePublishingAvailable();
        boolean typedReplyReady = artifacts.typedReplyCreationAvailable();
        boolean sandboxConnectorReady = authenticated && sandboxConnector.available(user);
        boolean eventRemindersReady = authenticated && eventReminders.available(user);
        boolean syntheticCatalogueReady = authenticated && syntheticCatalogue.available(user);
        boolean organizationReady = authenticated && organizations.availableIn(user);
        boolean organizationUsageReady = organizationReady && organizations.usageAvailable(user);
        boolean organizationPolicyReady = authenticated && organizationPolicies.availableIn(user);
        boolean proactiveReady = authenticated && proactiveAssistant.available(user);
        boolean hostingReady = authenticated && hostedSites.available(user);
        return ok(new Capabilities(List.of(
            new Capability("sign_in", true, null, null),
            new Capability("account_preferences", authenticated,
                authenticated ? null : "SIGN_IN_REQUIRED",
                authenticated ? null : "sign_in"),
            new Capability("projects", authenticated,
                authenticated ? null : "SIGN_IN_REQUIRED",
                authenticated ? null : "sign_in"),
            new Capability("project_resources", authenticated && projectResourcesReady,
                authenticated && projectResourcesReady ? null : projectResourcesUnavailable,
                !authenticated && projectResourcesReady ? "sign_in" : null),
            new Capability("project_attachments", authenticated && attachmentsReady,
                attachmentsReady ? authenticated ? null : "SIGN_IN_REQUIRED" : "POLICY_DISABLED",
                !authenticated && attachmentsReady ? "sign_in" : null),
            feature("project_local_image_edits", authenticated, localImageEditsReady),
            feature("project_audio_uploads", authenticated, audioUploadsReady),
            new Capability("project_audio_attachments", audioManagementReady,
                audioManagementReady ? null
                    : authenticated ? "POLICY_DISABLED" : "SIGN_IN_REQUIRED",
                authenticated ? null : "sign_in"),
            feature("project_pdf_uploads", authenticated, pdfUploadsReady),
            feature("project_general_pdf", authenticated, attachments.generalPdfAvailable()),
            feature("project_pdf_ocr", authenticated, attachments.pdfOcrAvailable()),
            new Capability("project_pdf_attachments", pdfManagementReady,
                pdfManagementReady ? null : authenticated ? "POLICY_DISABLED" : "SIGN_IN_REQUIRED",
                authenticated ? null : "sign_in"),
            new Capability("manual_memory", authenticated && memoryReady,
                authenticated && memoryReady ? null : memoryUnavailable,
                !authenticated && memoryReady ? "sign_in" : null),
            new Capability("data_analysis", authenticated && analysisReady,
                authenticated && analysisReady ? null : analysisUnavailable,
                !authenticated && analysisReady ? "sign_in" : null),
            new Capability("background_tasks", jobsEnabled && authenticated,
                jobsEnabled ? authenticated ? null : "SIGN_IN_REQUIRED" : "POLICY_DISABLED",
                jobsEnabled && !authenticated ? "sign_in" : null),
            new Capability("text_chat", textAvailable,
                textAvailable ? null : textUnavailable,
                authenticated ? null : "sign_in"),
            new Capability("temporary_chat", authenticated && temporaryReady,
                temporaryReady ? authenticated ? null : "SIGN_IN_REQUIRED" : "POLICY_DISABLED",
                !authenticated && temporaryReady ? "sign_in" : null),
            new Capability("account-erasure", authenticated && erasureReady,
                authenticated && erasureReady ? null : erasureUnavailable,
                !authenticated && erasureReady ? "sign_in" : null),
            new Capability("conversation_sharing", authenticated && shareReady,
                authenticated && shareReady ? null : shareUnavailable,
                !authenticated && shareReady ? "sign_in" : null),
            feature("shared_reply_reporting", authenticated, shareReady),
            new Capability("source_retrieval", authenticated && retrievalEnabled,
                authenticated && retrievalEnabled ? null : retrievalUnavailable,
                !authenticated && retrievalEnabled ? "sign_in" : null),
            new Capability("curated_research", authenticated && researchReady,
                authenticated && researchReady ? null : researchUnavailable,
                !authenticated && researchReady ? "sign_in" : null),
            new Capability("scheduled_reminders", authenticated && remindersReady,
                authenticated && remindersReady ? null : remindersUnavailable,
                !authenticated && remindersReady ? "sign_in" : null),
            new Capability("reminder_templates", authenticated && reminderTemplatesReady,
                authenticated && reminderTemplatesReady ? null : reminderTemplatesUnavailable,
                !authenticated && reminderTemplatesReady ? "sign_in" : null),
            feature("reminder_share_reporting", authenticated, reminderTemplatesReady),
            new Capability("custom_assistants", authenticated && customAssistantsReady,
                authenticated && customAssistantsReady ? null : customAssistantsUnavailable,
                !authenticated && customAssistantsReady ? "sign_in" : null),
            new Capability("local_custom_assistant_runs", authenticated && customRunsReady,
                authenticated && customRunsReady ? null : customRunsUnavailable,
                !authenticated && customRunsReady ? "sign_in" : null),
            new Capability("project_collaboration", authenticated && projectCollaborationReady,
                authenticated && projectCollaborationReady ? null : projectCollaborationUnavailable,
                !authenticated && projectCollaborationReady ? "sign_in" : null),
            new Capability("private_artifacts", authenticated && artifactsReady,
                authenticated && artifactsReady ? null : artifactsUnavailable,
                !authenticated && artifactsReady ? "sign_in" : null),
            new Capability("project_artifact_publications",
                authenticated && projectArtifactPublicationsReady,
                authenticated && projectArtifactPublicationsReady ? null
                    : projectArtifactPublicationsUnavailable,
                !authenticated && projectArtifactPublicationsReady ? "sign_in" : null),
            new Capability("private_spreadsheets", authenticated && spreadsheetsReady,
                authenticated && spreadsheetsReady ? null : spreadsheetsUnavailable,
                !authenticated && spreadsheetsReady ? "sign_in" : null),
            new Capability("project_artifact_comments",
                authenticated && projectArtifactCommentsReady,
                authenticated && projectArtifactCommentsReady ? null
                    : projectArtifactCommentsUnavailable,
                !authenticated && projectArtifactCommentsReady ? "sign_in" : null),
            new Capability("project_shared_drafts", authenticated && projectSharedDraftsReady,
                authenticated && projectSharedDraftsReady ? null : projectSharedDraftsUnavailable,
                !authenticated && projectSharedDraftsReady ? "sign_in" : null),
            new Capability("private_slides", authenticated && slidesReady,
                authenticated && slidesReady ? null : slidesUnavailable,
                !authenticated && slidesReady ? "sign_in" : null),
            new Capability("private_code_drafts", authenticated && codeReady,
                authenticated && codeReady ? null : codeUnavailable,
                !authenticated && codeReady ? "sign_in" : null),
            new Capability("workspace_departure", authenticated && workspaceDepartureReady,
                authenticated && workspaceDepartureReady ? null : workspaceDepartureUnavailable,
                !authenticated && workspaceDepartureReady ? "sign_in" : null),
            new Capability("private_webpages", authenticated && webpagesReady,
                authenticated && webpagesReady ? null : webpagesUnavailable,
                !authenticated && webpagesReady ? "sign_in" : null),
            new Capability("project_webpage_publications",
                authenticated && webpagePublicationsReady,
                authenticated && webpagePublicationsReady ? null : webpagePublicationsUnavailable,
                !authenticated && webpagePublicationsReady ? "sign_in" : null),
            new Capability("conversation_read_lock", authenticated && readingLockReady,
                authenticated && readingLockReady ? null
                    : readingLockReady ? "SIGN_IN_REQUIRED" : "POLICY_DISABLED",
                !authenticated && readingLockReady ? "sign_in" : null),
            new Capability("locked_conversations", lockedCatalogueReady,
                lockedCatalogueReady ? null
                    : authenticated ? "POLICY_DISABLED" : "SIGN_IN_REQUIRED",
                authenticated ? null : "sign_in"),
            new Capability("private_image_assets", authenticated && imageAssetsReady,
                authenticated && imageAssetsReady ? null
                    : imageAssetsReady ? "SIGN_IN_REQUIRED" : "POLICY_DISABLED",
                !authenticated && imageAssetsReady ? "sign_in" : null),
            new Capability("explicit_history_references", historyReferencesReady,
                historyReferencesReady ? null
                    : authenticated ? "POLICY_DISABLED" : "SIGN_IN_REQUIRED",
                authenticated ? null : "sign_in"),
            new Capability("project_image_publications", authenticated && imagePublicationsReady,
                authenticated && imagePublicationsReady ? null
                    : imagePublicationsReady ? "SIGN_IN_REQUIRED" : "POLICY_DISABLED",
                !authenticated && imagePublicationsReady ? "sign_in" : null),
            feature("project_spreadsheet_publications", authenticated,
                spreadsheetPublicationsReady),
            feature("project_slide_publications", authenticated, slidePublicationsReady),
            feature("project_code_publications", authenticated, codePublicationsReady),
            feature("typed_reply_artifacts", authenticated, typedReplyReady),
            new Capability("sandbox_connector", sandboxConnectorReady,
                sandboxConnectorReady ? null
                    : authenticated ? "POLICY_DISABLED" : "SIGN_IN_REQUIRED",
                authenticated ? null : "sign_in"),
            feature("sandbox_event_reminders", authenticated, eventRemindersReady),
            feature("synthetic_product_catalogue", authenticated, syntheticCatalogueReady),
            feature("organizations", authenticated, organizationReady),
            feature("organization_usage", authenticated, organizationUsageReady),
            feature("local_proactive_assistant", authenticated, proactiveReady),
            feature("local_desktop_companion", authenticated,
                authenticated && desktopCompanion.available(user)),
            feature("local_guardian_eligibility", authenticated,
                authenticated && eligibility.availableIn(user)),
            feature("public_webpage_hosting", authenticated, hostingReady),
            feature("hosted_site_reporting", authenticated, hostedSites.reportingAvailable()),
            feature("organization_policies", authenticated, organizationPolicyReady),
            feature("organization_model_budget", authenticated,
                authenticated && organizationModelBudgets.visibleTo(user)),
            feature("organization_directory", authenticated,
                authenticated && organizationDirectory.availableIn(user)),
            feature("reviewed_catalog", authenticated, catalog.available()),
            feature("reviewed_catalog_connector_submission", authenticated,
                authenticated && catalog.connectorSubmissionAvailable(user)),
            new Capability("screen_share", false, "POLICY_DISABLED", null))));
    }

    /**
     * Answers a bounded prompt without writing a conversation, run or memory.
     *
     * @param authorization consumer bearer header
     * @param workspace selected workspace
     * @param request byte-bounded JSON request and transport peer
     * @return temporary response
     */
    @PostMapping(value = "/temporary-chat", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RateLimiter(name = "assistant.temporary-chat", key = "#root.args[2].remoteAddr", rate = 12,
        interval = 1, unit = TimeUnit.MINUTES)
    public ResponseEntity<AssistantTemporaryChat.Reply> temporaryChat(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE_HEADER, required = false) String workspace,
        HttpServletRequest request) {
        var user = accounts.authenticate(authorization, workspace).user();
        var policy = organizationPolicies.effective(user.workspaceId(), false);
        if (!policy.allowsModel(AssistantTemporaryChat.MODEL_ID)) {
            throw AssistantOrganizationPolicies.denied();
        }
        organizationModelBudgets.requireTemporaryAllowed(user);
        var reply = temporaryChat.reply(request);
        var confirmed = accounts.authenticate(authorization, workspace).user();
        if (!organizationPolicies.effective(confirmed.workspaceId(), false)
            .matches(policy.revision())) {
            throw AssistantOrganizationPolicies.denied();
        }
        organizationModelBudgets.requireTemporaryAllowed(confirmed);
        return ok(reply);
    }

    /**
     * Translates consumer API errors without wrapping or logging credentials.
     *
     * @param exception consumer API error
     * @return HTTP error
     */
    @ExceptionHandler(AssistantApiException.class)
    public ResponseEntity<ApiError> handleApiError(AssistantApiException exception) {
        return error(exception.status(), exception.code(), exception.getMessage());
    }

    /**
     * Returns the consumer error envelope for an invalid JSON request.
     *
     * @return HTTP validation error
     */
    @ExceptionHandler({HttpMessageNotReadableException.class,
        MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiError> handleInvalidRequest() {
        return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
            "A JSON request body is required.");
    }

    /**
     * Returns the consumer error envelope when a client exceeds the limit.
     *
     * @return HTTP rate-limit error
     */
    @ExceptionHandler(RateLimiterException.class)
    public ResponseEntity<ApiError> handleRateLimit() {
        return error(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "Try again later.");
    }

    /**
     * Maps storage errors without disclosing SQL details.
     *
     * @return HTTP service error
     */
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ApiError> handleStorageError() {
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
            "The account service is unavailable.");
    }

    private static Capability feature(String id, boolean authenticated, boolean enabled) {
        return new Capability(id, authenticated && enabled,
            !enabled ? "POLICY_DISABLED" : authenticated ? null : "SIGN_IN_REQUIRED",
            enabled && !authenticated ? "sign_in" : null);
    }

    private static <T> ResponseEntity<T> ok(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    private static ResponseEntity<Void> noContent() {
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    private static ResponseEntity<ApiError> error(HttpStatus status, String code, String detail) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
            .body(new ApiError(code, detail));
    }

    /**
     * Feature availability list.
     *
     * @param capabilities visible features
     */
    public record Capabilities(List<Capability> capabilities) {
    }

    /**
     * Availability and fallback for a feature.
     *
     * @param id capability identifier
     * @param available whether access is allowed
     * @param reason reason code when unavailable
     * @param fallback fallback capability when available
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Capability(String id, boolean available, String reason, String fallback) {
    }

    /**
     * Consumer error envelope.
     *
     * @param code stable error code
     * @param message brief error message
     */
    public record ApiError(String code, String message) {
    }
}

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

package top.continew.admin.assistant.project;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import top.continew.admin.assistant.account.AssistantAccounts.Principal;
import top.continew.admin.assistant.account.AssistantApiException;
import top.continew.admin.assistant.artifact.AssistantArtifactImages;
import top.continew.admin.assistant.artifact.AssistantArtifactImages.SharedImage;
import top.continew.admin.assistant.artifact.AssistantArtifacts;
import top.continew.admin.assistant.artifact.AssistantArtifacts.Download;
import top.continew.admin.assistant.artifact.AssistantArtifacts.PublicationSource;
import top.continew.admin.assistant.artifact.AssistantCodeDraft.Code;
import top.continew.admin.assistant.artifact.AssistantPresentation.Presentation;
import top.continew.admin.assistant.artifact.AssistantSpreadsheet.Sheet;
import top.continew.admin.assistant.artifact.AssistantWebpage.Website;
import top.continew.admin.assistant.attachment.AssistantAttachments;
import top.continew.admin.assistant.attachment.AssistantAttachments.DownloadGrant;
import top.continew.admin.assistant.attachment.AssistantAttachments.PublishedSource;
import top.continew.admin.assistant.privacy.AssistantDeletionJournal;
import top.continew.admin.assistant.privacy.AssistantErasure;
import top.continew.admin.assistant.project.AssistantProjectCollaboration.Items;
import top.continew.admin.assistant.project.AssistantProjectCollaboration.PublicationScope;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Publishes an exact private artifact version as a revocable project reference.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
@Service
@ConditionalOnProperty(name = "assistant.enabled", havingValue = "true")
public class AssistantProjectArtifactPublications {

    private static final int MAX_ACTIVE_PER_PROJECT = 32;
    private static final String SELECT = """
        SELECT id, artifact_id, version_id, source_policy_revision, published_at
        FROM assistant_project_artifact_publication
        WHERE workspace_id = ? AND owner_id = ? AND project_id = ?
        """;
    private static final RowMapper<Stored> STORED = (row, index) -> new Stored(row.getString("id"),
        row.getString("artifact_id"), row.getString("version_id"),
        row.getString("source_policy_revision"), Instant.ofEpochMilli(row.getLong("published_at")));

    private final JdbcTemplate jdbc;
    private final AssistantProjectCollaboration collaboration;
    private final AssistantArtifacts artifacts;
    private final AssistantAttachments attachments;
    private final AssistantDeletionJournal journal;
    private final AssistantErasure erasure;
    private final boolean enabled;
    private final boolean webpagesEnabled;
    private final boolean imagesEnabled;
    private final boolean spreadsheetsEnabled;
    private final boolean slidesEnabled;
    private final boolean codeEnabled;

    /**
     * Connects collaboration permissions, private artifact versions and durable revocation.
     *
     * @param jdbc publication references
     * @param collaboration project permissions
     * @param artifacts exact scoped source versions
     * @param attachments authorized access to the approved PNG store
     * @param journal durable source and publication decisions
     * @param erasure publication revocation
     * @param enabled opt-in feature
     * @param webpagesEnabled optional scoped webpage publication policy
     * @param imagesEnabled optional scoped PNG publication policy
     * @param spreadsheetsEnabled scoped workbook publication policy
     * @param slidesEnabled scoped presentation publication policy
     * @param codeEnabled scoped literal code publication policy
     */
    public AssistantProjectArtifactPublications(JdbcTemplate jdbc,
        AssistantProjectCollaboration collaboration, AssistantArtifacts artifacts,
        AssistantAttachments attachments,
        AssistantDeletionJournal journal, AssistantErasure erasure,
        @Value("${assistant.project-artifact-publications.enabled:false}") boolean enabled,
        @Value("${assistant.project-webpage-publications.enabled:false}") boolean webpagesEnabled,
        @Value("${assistant.project-image-publications.enabled:false}") boolean imagesEnabled,
        @Value("${assistant.project-spreadsheet-publications.enabled:false}") boolean spreadsheetsEnabled,
        @Value("${assistant.project-slide-publications.enabled:false}") boolean slidesEnabled,
        @Value("${assistant.project-code-publications.enabled:false}") boolean codeEnabled) {
        this.jdbc = jdbc;
        this.collaboration = collaboration;
        this.artifacts = artifacts;
        this.attachments = attachments;
        this.journal = journal;
        this.erasure = erasure;
        this.enabled = enabled;
        this.webpagesEnabled = webpagesEnabled;
        this.imagesEnabled = imagesEnabled;
        this.spreadsheetsEnabled = spreadsheetsEnabled;
        this.slidesEnabled = slidesEnabled;
        this.codeEnabled = codeEnabled;
    }

    /**
     * Reports if the deployment can serve approved reviewed PNG publications.
     *
     * @return true when the exact-source, private object and deletion gates are ready
     */
    public boolean imagePublishingAvailable() {
        return enabled && imagesEnabled && artifacts.available() && attachments.available();
    }

    /**
     * Reports whether reviewed workbook publications can be served.
     *
     * @return true when the workbook and base publication policies are enabled
     */
    public boolean spreadsheetPublishingAvailable() {
        return enabled && spreadsheetsEnabled && artifacts.available();
    }

    /**
     * Reports whether reviewed presentation publications can be served.
     *
     * @return true when the presentation and base publication policies are enabled
     */
    public boolean slidePublishingAvailable() {
        return enabled && slidesEnabled && artifacts.available();
    }

    /**
     * Reports whether reviewed literal code publications can be served.
     *
     * @return true when the code and base publication policies are enabled
     */
    public boolean codePublishingAvailable() {
        return enabled && codeEnabled && artifacts.available();
    }

    /**
     * Publishes one explicitly confirmed version under the project owner's current scope.
     *
     * @param principal project owner's current session
     * @param projectId owning project
     * @param artifactId approved source artifact
     * @param versionId approved immutable version
     * @param confirm explicit publication consent
     * @return new publication metadata
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Summary publish(Principal principal, String projectId, String artifactId,
        String versionId, boolean confirm) {
        requireReady();
        AssistantProjectCollaboration.requireId(artifactId);
        AssistantProjectCollaboration.requireId(versionId);
        if (!confirm) {
            throw invalid("Confirm publication of this exact artifact version.");
        }
        PublicationScope scope = collaboration.publicationScope(principal, projectId, true);
        scope.requireOwner();
        ProjectRef ref = project(principal, projectId, scope);
        PublicationSource source = artifacts.publicationSource(ref.ownerId(), ref.workspaceId(),
            ref.projectId(), artifactId, versionId, false);
        if (!canRead(source)) {
            throw missing();
        }
        int active = 0;
        for (Stored stored : storedForProject(ref)) {
            if (visibleSource(ref, stored, false) == null) {
                discardWithheld(ref, stored);
                continue;
            }
            if (stored.artifactId().equals(artifactId) && stored.versionId().equals(versionId)) {
                throw conflict("This exact artifact version already has an active publication.");
            }
            active++;
        }
        if (active >= MAX_ACTIVE_PER_PROJECT) {
            throw conflict("The project already has 32 active artifact publications.");
        }
        String id = UUID.randomUUID().toString();
        Instant now = Instant.ofEpochMilli(System.currentTimeMillis());
        jdbc.update("""
            INSERT INTO assistant_project_artifact_publication
                (id, workspace_id, owner_id, project_id, artifact_id, version_id,
                    source_policy_revision, published_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """, id, ref.workspaceId(), ref.ownerId(), ref.projectId(), artifactId, versionId,
            source.policyRevision(), now.toEpochMilli());
        return new Summary(id, source.title(), source.format(), source.versionNumber(),
            source.sizeBytes(), now);
    }

    /**
     * Lists the immutable version headings available to the current project member.
     *
     * @param principal consuming session
     * @param projectId collaboration project
     * @return authorized published version headings
     */
    public Items<Summary> list(Principal principal, String projectId) {
        requireReady();
        PublicationScope scope = collaboration.publicationScope(principal, projectId, false);
        ProjectRef ref = project(principal, projectId, scope);
        List<Visible> candidates = new ArrayList<>();
        for (Stored stored : storedForProject(ref)) {
            PublicationSource source = visibleSource(ref, stored, false);
            if (canRead(source)) {
                candidates.add(new Visible(stored, source));
            }
        }
        requireCurrentScope(principal, projectId, scope);
        return new Items<>(candidates.stream().filter(item -> stillVisible(ref, item.stored(),
            item.source())).map(item -> summarize(item.stored(), item.source())).toList());
    }

    /**
     * Reads one published immutable version through the current project role.
     *
     * @param principal consuming session
     * @param projectId collaboration project
     * @param id scoped publication identifier
     * @return authorized version with the reviewed text, structured webpage or image metadata
     */
    public Publication get(Principal principal, String projectId, String id) {
        Authorized read = authorizeRead(principal, projectId, id, false);
        var body = read.source().body();
        Publication result = new Publication(summarize(read.row(), read.source()),
            body.content(), body.website(), read.source().image(), body.sheet(),
            body.presentation(), body.code());
        requireAuthorized(principal, read);
        return result;
    }

    /**
     * Downloads the reviewed text, isolated webpage or approved PNG under current authority.
     *
     * @param principal consuming session
     * @param projectId collaboration project
     * @param id scoped publication identifier
     * @param token one-use publication authorization for a PNG
     * @return fixed-format bytes
     */
    public Download download(Principal principal, String projectId, String id, String token) {
        Authorized read = authorizeRead(principal, projectId, id, false);
        PublicationSource source = read.source();
        Download file = AssistantArtifactImages.FORMAT.equals(source.format())
            ? new Download(source.format(), source.versionNumber(),
                attachments.downloadPublication(principal, imageSource(read), token), null)
            : source.rendered();
        requireAuthorized(principal, read);
        return file;
    }

    /**
     * Issues a short-lived one-use PNG grant to the current owner or accepted member.
     *
     * @param principal actor and actual device session
     * @param projectId collaboration project
     * @param id exact approved publication identifier
     * @return authorization bound to this publication and its current role generation
     */
    public DownloadGrant imageGrant(Principal principal, String projectId, String id) {
        Authorized read = authorizeRead(principal, projectId, id, false);
        if (!AssistantArtifactImages.FORMAT.equals(read.source().format())) {
            throw missing();
        }
        var grant = attachments.grantPublication(principal, imageSource(read));
        requireAuthorized(principal, read);
        return grant;
    }

    private static PublishedSource imageSource(Authorized read) {
        return new PublishedSource(read.ref().ownerId(), read.ref().projectId(),
            read.source().imageSource().id(), read.row().id(), read.scope().grantId());
    }

    /**
     * Renders the exact webpage revision approved for signed-in members of this project.
     *
     * @param principal consuming session
     * @param projectId collaboration project
     * @param id scoped publication identifier
     * @return fixed-template UTF-8 HTML
     */
    public byte[] webpagePreview(Principal principal, String projectId, String id) {
        Authorized read = authorizeRead(principal, projectId, id, true);
        byte[] bytes = read.source().renderedWebsite();
        requireAuthorized(principal, read);
        return bytes;
    }

    /**
     * Retires one publication without changing the privately stored source version.
     *
     * @param principal project owner's current session
     * @param projectId owning project
     * @param id scoped publication identifier
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void revoke(Principal principal, String projectId, String id) {
        requireReady();
        AssistantProjectCollaboration.requireId(id);
        PublicationScope scope = collaboration.publicationScope(principal, projectId, true);
        scope.requireOwner();
        ProjectRef ref = project(principal, projectId, scope);
        storedForId(ref, id);
        erasure.revokeArtifactPublication(ref.ownerId(), ref.workspaceId(), id);
    }

    PublicationScope commentScope(Principal principal, String projectId, String id,
        boolean write) {
        requireReady();
        AssistantProjectCollaboration.requireId(id);
        PublicationScope scope = collaboration.publicationScope(principal, projectId, write);
        if (write) {
            scope.requireWriter();
        }
        ProjectRef ref = project(principal, projectId, scope);
        if (!canRead(visibleSource(ref, storedForId(ref, id), false))) {
            throw missing();
        }
        return scope;
    }

    private PublicationSource visibleSource(ProjectRef ref, Stored row, boolean includeContent) {
        if (journal.blocksArtifactPublication(ref.ownerId(), ref.workspaceId(), row.id())) {
            return null;
        }
        PublicationSource source = artifacts.publicationSource(ref.ownerId(), ref.workspaceId(),
            ref.projectId(), row.artifactId(), row.versionId(), includeContent);
        return source != null && stillVisible(ref, row, source) ? source : null;
    }

    private boolean canRead(PublicationSource source) {
        return source != null && switch (source.format()) {
            case "webpage" -> webpagesEnabled;
            case AssistantArtifactImages.FORMAT -> imagePublishingAvailable();
            case "sheet" -> spreadsheetPublishingAvailable();
            case "slides" -> slidePublishingAvailable();
            case "code" -> codePublishingAvailable();
            default -> true;
        };
    }

    private Authorized authorizeRead(Principal principal, String projectId, String id,
        boolean webpageOnly) {
        requireReady();
        AssistantProjectCollaboration.requireId(id);
        PublicationScope scope = collaboration.publicationScope(principal, projectId, false);
        ProjectRef ref = project(principal, projectId, scope);
        Stored row = storedForId(ref, id);
        PublicationSource metadata = visibleSource(ref, row, false);
        if (!canRead(metadata) || webpageOnly && !"webpage".equals(metadata.format())) {
            throw missing();
        }
        PublicationSource content = visibleSource(ref, row, true);
        if (!canRead(content)) {
            throw missing();
        }
        return new Authorized(ref, scope, row, content);
    }

    private void requireAuthorized(Principal principal, Authorized read) {
        ProjectRef ref = read.ref();
        requireCurrentScope(principal, ref.projectId(), read.scope());
        synchronized (journal) {
            String grant = read.scope().grantId();
            if (!stillVisible(ref, read.row(), read.source())
                || journal.blocksCollaborationParticipant(principal.user().id())
                || grant != null
                    && journal.blocksCollaborationGrant(ref.ownerId(), ref.workspaceId(), grant)) {
                throw missing();
            }
        }
    }

    private boolean stillVisible(ProjectRef ref, Stored row, PublicationSource source) {
        return Objects.equals(row.policyRevision(), source.policyRevision())
            && source.placementCurrent(journal, ref.ownerId(), ref.workspaceId())
            && !journal.blocksArtifactPublication(ref.ownerId(), ref.workspaceId(), row.id())
            && !journal.blocksArtifact(ref.ownerId(), ref.workspaceId(), row.artifactId(),
                source.conversationId())
            && (source.conversationId() == null || !journal.blocksConversationRead(ref.ownerId(),
                ref.workspaceId(), source.conversationId(), source.readRevision()))
            && !journal.blocksArtifactPublicationPolicy(ref.ownerId(), ref.workspaceId(),
                source.conversationId(), row.policyRevision())
            && (source.imageSource() == null || !journal.blocksAttachment(ref.ownerId(),
                ref.workspaceId(), source.imageSource().id()));
    }

    private void requireCurrentScope(Principal principal, String projectId,
        PublicationScope before) {
        requireReady();
        if (!before.equals(collaboration.publicationScope(principal, projectId, false))) {
            throw missing();
        }
    }

    private List<Stored> storedForProject(ProjectRef ref) {
        return jdbc.query(SELECT + " ORDER BY published_at DESC, id DESC LIMIT ?", STORED,
            ref.workspaceId(), ref.ownerId(), ref.projectId(), MAX_ACTIVE_PER_PROJECT);
    }

    private Stored storedForId(ProjectRef ref, String id) {
        return jdbc.query(SELECT + " AND id = ?", STORED, ref.workspaceId(), ref.ownerId(),
            ref.projectId(), id).stream().findFirst()
            .orElseThrow(AssistantProjectArtifactPublications::missing);
    }

    private void discardWithheld(ProjectRef ref, Stored row) {
        // Deletion and policy revision tombstones already prevent this withheld reference from reviving.
        jdbc.update("""
            DELETE FROM assistant_project_artifact_publication
            WHERE workspace_id = ? AND owner_id = ? AND project_id = ? AND id = ?
            """, ref.workspaceId(), ref.ownerId(), ref.projectId(), row.id());
    }

    private void requireReady() {
        if (!enabled || !journal.enabled()) {
            throw new AssistantApiException(HttpStatus.FORBIDDEN, "CAPABILITY_UNAVAILABLE",
                "Project artifact publications are unavailable.");
        }
        if (!journal.acceptsErasure()) {
            throw new AssistantApiException(HttpStatus.SERVICE_UNAVAILABLE,
                "CAPABILITY_UNAVAILABLE",
                "Project publication revocation is temporarily unavailable.");
        }
        if (!artifacts.available()) {
            throw new AssistantApiException(HttpStatus.FORBIDDEN, "CAPABILITY_UNAVAILABLE",
                "Project artifact publications are unavailable.");
        }
    }

    private static ProjectRef project(Principal principal, String projectId,
        PublicationScope scope) {
        return new ProjectRef(principal.user().workspaceId(), scope.ownerId(), projectId);
    }

    private static Summary summarize(Stored row, PublicationSource source) {
        return new Summary(row.id(), source.title(), source.format(), source.versionNumber(),
            source.sizeBytes(), row.publishedAt());
    }

    private static AssistantApiException missing() {
        return new AssistantApiException(HttpStatus.NOT_FOUND, "NOT_FOUND",
            "Project publication or eligible source was not found.");
    }

    private static AssistantApiException invalid(String message) {
        return new AssistantApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message);
    }

    private static AssistantApiException conflict(String message) {
        return new AssistantApiException(HttpStatus.CONFLICT, "CONFLICT", message);
    }

    /**
     * Public projection scoped to the current project role.
     *
     * @param id publication identifier
     * @param title approved immutable version title
     * @param format text, Markdown, webpage or approved PNG image
     * @param versionNumber immutable version ordinal
     * @param sizeBytes exact UTF-8 size or approved PNG byte length
     * @param publishedAt publication time
     */
    public record Summary(String id, String title, String format, int versionNumber, int sizeBytes,
        Instant publishedAt) {
    }

    /**
     * Original content of a project-scoped immutable publication.
     *
     * @param summary scoped publication metadata
     * @param content immutable version text
     * @param website structured webpage when the source is a webpage
     * @param image approved PNG geometry and alternative text
     * @param sheet reviewed typed workbook cells
     * @param presentation reviewed slide headings and bullets
     * @param code reviewed literal source and its declared language
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Publication(@JsonUnwrapped Summary summary, String content, Website website,
        SharedImage image, Sheet sheet, Presentation presentation, Code code) {

        @Override
        public String toString() {
            return "Publication[protected]";
        }
    }

    private record ProjectRef(String workspaceId, String ownerId, String projectId) {
    }

    private record Stored(String id, String artifactId, String versionId, String policyRevision,
        Instant publishedAt) {
    }

    private record Visible(Stored stored, PublicationSource source) {
    }

    private record Authorized(ProjectRef ref, PublicationScope scope, Stored row,
        PublicationSource source) {
    }
}

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

package top.continew.admin.assistant.artifact;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.continew.admin.assistant.account.AssistantAccounts;
import top.continew.admin.assistant.account.AssistantAccounts.Principal;
import top.continew.admin.assistant.account.AssistantAccounts.User;
import top.continew.admin.assistant.account.AssistantApiException;
import top.continew.admin.assistant.attachment.AssistantAttachments;
import top.continew.admin.assistant.attachment.AssistantAttachments.DownloadGrant;
import top.continew.admin.assistant.privacy.AssistantDeletionJournal;
import top.continew.admin.assistant.privacy.AssistantErasure;
import top.continew.admin.assistant.project.AssistantProjects;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Owns bounded private drafts and immutable, explicitly selected versions.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
@Service
@ConditionalOnProperty(name = "assistant.enabled", havingValue = "true")
public class AssistantArtifacts {

    private static final int MAX_ARTIFACTS = 64;
    private static final int MAX_VERSIONS = 32;
    private static final int MAX_BYTES = 32 * 1024;
    private static final int MAX_TITLE = 120;
    private static final String SOURCE =
        """
            SELECT c.id AS conversation_id, c.project_id, c.read_lock_revision,
                CASE WHEN OCTET_LENGTH(m.content) <= 32768 THEN m.content END AS content
            FROM assistant_chat_message m
            JOIN assistant_conversation c ON c.id = m.conversation_id
            WHERE c.account_id = ? AND c.workspace_id = ? AND c.deleted_at IS NULL AND c.read_locked = FALSE
                AND m.id = ? AND m.role = 'assistant' AND EXISTS (
                    SELECT 1 FROM assistant_run r WHERE r.workspace_id = c.workspace_id
                        AND r.account_id = c.account_id AND r.conversation_id = c.id
                        AND r.response_message_id = m.id AND r.state = 'completed')
            """;
    private static final String ARTIFACTS =
        """
            SELECT a.id, a.project_id, a.source_conversation_id, a.source_message_id, a.format,
                a.head_version_id, a.created_at, a.updated_at, a.request_digest,
                v.version_no, v.title, v.size_bytes, c.read_lock_revision,
            """ + AssistantArtifactImages.SOURCE_PROJECTION + """
            FROM assistant_artifact a
            JOIN assistant_artifact_version v ON v.artifact_id = a.id AND v.id = a.head_version_id
            """ + AssistantArtifactImages.SOURCE_JOIN
            + """
                LEFT JOIN assistant_conversation c ON c.id = a.source_conversation_id
                    AND c.account_id = a.account_id AND c.workspace_id = a.workspace_id
                WHERE a.account_id = ? AND a.workspace_id = ?
                    AND (a.project_id IS NULL OR EXISTS (
                        SELECT 1 FROM assistant_project p WHERE p.id = a.project_id
                            AND p.account_id = a.account_id AND p.workspace_id = a.workspace_id AND p.deleted_at IS NULL))
                    AND (a.source_conversation_id IS NULL
                        OR (c.id IS NOT NULL AND c.deleted_at IS NULL AND c.read_locked = FALSE))
                """;
    private static final String VERSION_SUMMARIES =
        """
            SELECT v.id, v.artifact_id, v.version_no, v.title, v.size_bytes, v.created_at,
                v.restored_from_version_id,
            """ + AssistantArtifactImages.SOURCE_PROJECTION + """
            FROM assistant_artifact_version v
            """ + AssistantArtifactImages.SOURCE_JOIN + """
            WHERE v.artifact_id = ? AND v.version_no < ? ORDER BY v.version_no DESC LIMIT 32
            """;
    private static final String VERSION = """
        SELECT v.id, v.artifact_id, v.version_no, v.title, v.size_bytes, v.created_at,
            v.restored_from_version_id,
            CASE WHEN OCTET_LENGTH(v.content) <= 32768 THEN v.content END AS content,
        """ + AssistantArtifactImages.SOURCE_PROJECTION + """
        FROM assistant_artifact_version v
        """ + AssistantArtifactImages.SOURCE_JOIN + """
        WHERE v.artifact_id = ? AND v.id = ?
        """;
    private static final String PUBLISHABLE =
        """
            SELECT a.format, a.source_conversation_id, TRIM(c.sharing_policy_revision) AS policy_revision,
                v.title, v.version_no, v.size_bytes, c.read_lock_revision, a.source_placement_revision,
            """
            + AssistantArtifactImages.SOURCE_PROJECTION + """
                FROM assistant_artifact a
                JOIN assistant_artifact_version v ON v.artifact_id = a.id
                """ + AssistantArtifactImages.SOURCE_JOIN
            + """
                JOIN assistant_project p ON p.id = a.project_id AND p.workspace_id = a.workspace_id
                    AND p.account_id = a.account_id AND p.deleted_at IS NULL
                LEFT JOIN assistant_conversation c ON c.id = a.source_conversation_id
                    AND c.workspace_id = a.workspace_id AND c.account_id = a.account_id
                WHERE a.account_id = ? AND a.workspace_id = ? AND a.project_id = ?
                    AND a.id = ? AND v.id = ? AND a.format IN ('text', 'markdown', 'webpage', 'image', 'sheet', 'slides', 'code')
                    AND (a.format <> 'image' OR approved_image.id IS NOT NULL)
                    AND (a.source_conversation_id IS NULL
                        OR (c.id IS NOT NULL AND c.deleted_at IS NULL AND c.sharing_restricted = FALSE
                            AND c.read_locked = FALSE
                            AND (a.format IN ('text', 'markdown', 'webpage') OR c.project_id = a.project_id)))
                """;
    private static final RowMapper<Stored> ARTIFACT_ROW = (row, index) -> new Stored(
        new Summary(row.getString("id"), row.getString("project_id"),
            row.getString("source_message_id"),
            row.getString("format"), row.getString("head_version_id"), row.getInt("version_no"),
            row.getString("title"), row.getInt("size_bytes"), instant(row, "created_at"),
            instant(row, "updated_at"), null),
        row.getString("source_conversation_id"), row.getString("read_lock_revision"),
        row.getString("request_digest"), AssistantArtifactImages.source(row));
    private static final RowMapper<StoredVersion> VERSION_SUMMARY_ROW =
        (row, index) -> new StoredVersion(versionSummary(row), null,
            AssistantArtifactImages.source(row));
    private static final RowMapper<StoredVersion> VERSION_ROW =
        (row, index) -> new StoredVersion(versionSummary(row),
            row.getString("content"), AssistantArtifactImages.source(row));
    private static final RowMapper<PublicationSource> PUBLICATION_SOURCE =
        AssistantArtifacts::readPublicationSource;

    private final JdbcTemplate jdbc;
    private final AssistantAccounts accounts;
    private final AssistantProjects projects;
    private final AssistantDeletionJournal journal;
    private final AssistantErasure erasure;
    private final AssistantAttachments attachments;
    private final ObjectMapper mapper;
    private final boolean enabled;
    private final boolean spreadsheetsEnabled;
    private final boolean slidesEnabled;
    private final boolean codeEnabled;
    private final boolean webpagesEnabled;
    private final boolean imagesEnabled;
    private final boolean typedReplyEnabled;

    /**
     * Wires private storage, consumer ownership and independent deletion.
     *
     * @param jdbc product database
     * @param accounts account locks before mutations
     * @param projects project ownership
     * @param journal current privacy policy and tombstones
     * @param erasure durable artifact deletion
     * @param attachments private approved project PNG lifecycle and grants
     * @param mapper app JSON encoding for canonical structured storage
     * @param enabled deployment feature switch
     * @param spreadsheetsEnabled private workbook editing switch
     * @param slidesEnabled private presentation editing switch
     * @param codeEnabled private code draft editing switch
     * @param webpagesEnabled private webpage editing switch
     * @param imagesEnabled private image editing switch
     * @param typedReplyEnabled opt-in for reviewed typed artifacts created from replies
     */
    public AssistantArtifacts(JdbcTemplate jdbc, AssistantAccounts accounts,
        AssistantProjects projects,
        AssistantDeletionJournal journal, AssistantErasure erasure,
        AssistantAttachments attachments,
        ObjectMapper mapper, @Value("${assistant.artifacts.enabled:false}") boolean enabled,
        @Value("${assistant.artifacts.spreadsheets.enabled:false}") boolean spreadsheetsEnabled,
        @Value("${assistant.artifacts.slides.enabled:false}") boolean slidesEnabled,
        @Value("${assistant.artifacts.code.enabled:false}") boolean codeEnabled,
        @Value("${assistant.artifacts.webpages.enabled:false}") boolean webpagesEnabled,
        @Value("${assistant.artifacts.images.enabled:false}") boolean imagesEnabled,
        @Value("${assistant.artifacts.reply-typed-enabled:false}") boolean typedReplyEnabled) {
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.projects = projects;
        this.journal = journal;
        this.erasure = erasure;
        this.attachments = attachments;
        this.mapper = mapper;
        this.enabled = enabled;
        this.spreadsheetsEnabled = spreadsheetsEnabled;
        this.slidesEnabled = slidesEnabled;
        this.codeEnabled = codeEnabled;
        this.webpagesEnabled = webpagesEnabled;
        this.imagesEnabled = imagesEnabled;
        this.typedReplyEnabled = typedReplyEnabled;
    }

    /**
     * Reports whether the feature can enforce durable revocation.
     *
     * @return true only when enabled and the journal is ready
     */
    public boolean available() {
        return enabled && journal.acceptsErasure();
    }

    /**
     * Reports whether an owner can create and edit a versioned reference to a scanned PNG.
     *
     * @return true when the artifact, attachment and editing policies permit writes
     */
    public boolean imageEditingAvailable() {
        return available() && imagesEnabled && attachments.available();
    }

    /**
     * Reports whether reviewed private webpages are enabled and revocation is available.
     *
     * @return true for a ready webpage deployment
     */
    public boolean webpageEditingAvailable() {
        return available() && webpagesEnabled;
    }

    /**
     * Reports if clients may create a reviewed typed version from a completed owned reply.
     *
     * @return true when this deployment enables the additional reply provenance formats
     */
    public boolean typedReplyCreationAvailable() {
        return available() && typedReplyEnabled;
    }

    /**
     * Creates a fixed-scope draft or returns its matching idempotent request.
     *
     * @param owner authenticated account and workspace
     * @param key canonical client retry UUID
     * @param input draft and source selection
     * @return current immutable head
     */
    @Transactional
    public Artifact create(User owner, String key, Create input) {
        requireReady();
        requireId(key);
        String title = title(input.title());
        requireFormat(input.format());
        requireFormatEditing(input.format());
        boolean websiteFromReply = AssistantWebpage.FORMAT.equals(input.format())
            && input.sourceMessageId() != null;
        boolean typedFromReply =
            typedReplyFormat(input.format()) && input.sourceMessageId() != null;
        if (typedFromReply && !typedReplyCreationAvailable()) {
            throw new AssistantApiException(HttpStatus.FORBIDDEN, "CAPABILITY_UNAVAILABLE",
                "Creating typed artifacts from replies is unavailable.");
        }
        if (typedFromReply && !Boolean.TRUE.equals(input.confirmSource())
            || !typedFromReply && input.confirmSource() != null) {
            throw invalid("Confirm the exact completed reply for this reviewed artifact.");
        }
        if (websiteFromReply && input.body().fieldCount() != 0) {
            throw invalid(
                "Generate from the selected reply or supply a structured project webpage.");
        }
        if (!websiteFromReply && !typedFromReply && structured(input.format())
            && (input.projectId() == null || input.sourceMessageId() != null)) {
            throw invalid(
                "This artifact format belongs to an owned project and uses its own structured contents.");
        }
        String requestedContent =
            websiteFromReply ? null : artifactBody(input.format(), input.body());
        if (input.projectId() == null && input.sourceMessageId() == null
            || input.sourceMessageId() == null && requestedContent == null) {
            throw invalid("Choose a project and text, or an owned completed reply.");
        }
        if (input.projectId() != null) {
            requireId(input.projectId());
        }
        if (input.sourceMessageId() != null) {
            requireId(input.sourceMessageId());
        }
        Integer suppliedSize = requestedContent == null ? null : size(requestedContent);
        String id = identity(owner, key);
        lockScope(owner);
        if (blocked(owner, id, null)) {
            throw notFound();
        }
        String fingerprint =
            HexFormat.of().formatHex(digest(input.projectId(), input.sourceMessageId(), title,
                input.format(), requestedContent));
        var previous = stored(owner, id);
        if (previous != null) {
            requireLive(owner, previous);
            if (!previous.requestDigest().equals(fingerprint)) {
                throw conflict("This request key belongs to a different artifact.");
            }
            return artifact(owner, previous);
        }
        Source source =
            input.sourceMessageId() == null ? null : source(owner, id, input.sourceMessageId());
        String projectId = source == null ? input.projectId() : source.projectId();
        if (input.projectId() != null && !Objects.equals(projectId, input.projectId())) {
            throw notFound();
        }
        if (AssistantArtifactImages.FORMAT.equals(input.format()) && projectId == null) {
            throw invalid("Choose an approved image in the project of this reply.");
        }
        if (projectId != null) {
            projects.requireOwned(owner, projectId);
        }
        String content =
            requestedContent != null ? requestedContent : Objects.requireNonNull(source).content();
        if (websiteFromReply) {
            content = AssistantWebpage.encode(mapper, AssistantWebpage.fromReply(title, content));
        }
        int size = suppliedSize == null ? size(content) : suppliedSize;
        if (jdbc.queryForList("""
            SELECT id FROM assistant_artifact WHERE workspace_id = ? AND account_id = ? LIMIT 64
            """, String.class, owner.workspaceId(), owner.id()).size() >= MAX_ARTIFACTS) {
            throw conflict("The workspace has reached its private artifact limit.");
        }
        long now = System.currentTimeMillis();
        String version = UUID.randomUUID().toString();
        String sourcePlacement = typedFromReply
            ? journal.historyPlacementRevision(owner.id(), owner.workspaceId(),
                Objects.requireNonNull(source).conversationId())
            : null;
        jdbc.update(
            """
                INSERT INTO assistant_artifact
                    (id, account_id, workspace_id, project_id, source_conversation_id, source_message_id,
                     format, head_version_id, request_digest, created_at, updated_at, source_placement_revision)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
            id, owner.id(), owner.workspaceId(), projectId,
            source == null ? null : source.conversationId(),
            input.sourceMessageId(), input.format(), version, fingerprint, now, now,
            sourcePlacement);
        putVersion(id, version, 1, title, content, size, null, now);
        bindImage(owner, projectId, input.format(), id, version, content);
        return artifact(owner, owned(owner, id));
    }

    /**
     * Lists metadata in the account's current workspace, optionally in one project.
     *
     * @param owner authenticated scope
     * @param projectId optional owned project
     * @param limit requested page size
     * @param cursor scoped continuation
     * @return bounded metadata and continuation
     */
    public Page<Summary> list(User owner, String projectId, Integer limit, String cursor) {
        requireReady();
        String section = "catalog-" + Objects.toString(projectId, "all");
        String last = cursor == null ? "" : readCursor(owner, section, cursor);
        if (cursor != null) {
            requireId(last);
        }
        int count = pageSize(limit);
        String query = ARTIFACTS + " AND a.id > ?";
        List<Stored> rows;
        if (projectId == null) {
            rows = jdbc.query(query + " ORDER BY a.id LIMIT 64", ARTIFACT_ROW, owner.id(),
                owner.workspaceId(), last);
        } else {
            projects.requireOwned(owner, projectId);
            rows = jdbc.query(query + " AND a.project_id = ? ORDER BY a.id LIMIT 64", ARTIFACT_ROW,
                owner.id(), owner.workspaceId(), last, projectId);
        }
        synchronized (journal) {
            requireReady();
            var visible = rows.stream()
                .filter(row -> !blocked(owner, row.summary().id(), row.conversationId())
                    && sourceOpen(owner.id(), owner.workspaceId(), row.conversationId(),
                        row.readRevision()))
                .limit(count + 1L).map(row -> catalogSummary(owner, row)).toList();
            var items = visible.subList(0, Math.min(visible.size(), count));
            String next = visible.size() > count
                ? cursor(owner, section, items.get(items.size() - 1).id()) : null;
            return new Page<>(items, next);
        }
    }

    /**
     * Reads the owner-selected artifact and current body.
     *
     * @param owner authenticated scope
     * @param id artifact identifier
     * @return current artifact and head version
     */
    public Artifact get(User owner, String id) {
        requireReady();
        return artifact(owner, owned(owner, id));
    }

    /**
     * Reads the artifact's bounded immutable version metadata.
     *
     * @param owner authenticated scope
     * @param id artifact identifier
     * @param limit requested page size
     * @param cursor scoped continuation
     * @return newest versions first with continuation
     */
    public Page<VersionSummary> versions(User owner, String id, Integer limit, String cursor) {
        requireReady();
        Stored row = owned(owner, id);
        String section = "versions-" + id;
        int last =
            cursor == null ? MAX_VERSIONS + 1 : versionNumber(readCursor(owner, section, cursor));
        int count = pageSize(limit);
        var records = jdbc.query(VERSION_SUMMARIES, VERSION_SUMMARY_ROW, id, last);
        synchronized (journal) {
            requireLive(owner, row);
            var visible = records.stream()
                .filter(item -> imageVisible(owner, row.summary().format(), item.imageSource()))
                .limit(count + 1L).map(StoredVersion::summary).toList();
            var items = visible.subList(0, Math.min(visible.size(), count));
            String next = visible.size() > count
                ? cursor(owner, section, Integer.toString(items.get(items.size() - 1).number()))
                : null;
            return new Page<>(items, next);
        }
    }

    /**
     * Reads only the requested version of an owned artifact.
     *
     * @param owner authenticated scope
     * @param id artifact identifier
     * @param versionId immutable version identifier
     * @return stored version
     */
    public Version version(User owner, String id, String versionId) {
        requireReady();
        return readableVersion(owner, owned(owner, id), versionId);
    }

    /**
     * Projects one version for an already authorized project publication operation.
     *
     * @param ownerId validated project owner
     * @param workspaceId validated selected workspace
     * @param projectId chosen owning project
     * @param id scoped source artifact
     * @param versionId exact immutable version
     * @param includeContent whether to load and validate the original UTF-8 body
     * @return eligible current source, or null when the lineage is withheld
     */
    public PublicationSource publicationSource(String ownerId, String workspaceId, String projectId,
        String id, String versionId, boolean includeContent) {
        requireReady();
        requireId(id);
        requireId(versionId);
        PublicationSource source = jdbc.query(PUBLISHABLE, PUBLICATION_SOURCE, ownerId, workspaceId,
            projectId, id, versionId).stream().findFirst().orElse(null);
        if (source == null || blocksPublicationSource(ownerId, workspaceId, id, source)) {
            return null;
        }
        if (!includeContent) {
            return source;
        }
        StoredVersion stored = validatedVersion(id, versionId);
        if (source.imageSource() != null && !source.imageSource().equals(stored.imageSource())) {
            return null;
        }
        PublicationSource expanded = source.withBody(mapper, stored);
        if (blocksPublicationSource(ownerId, workspaceId, id, source)) {
            return null;
        }
        return expanded;
    }

    /**
     * Returns the exact text or a native Office file for the selected version.
     *
     * @param principal authenticated scope and device session
     * @param id artifact identifier
     * @param versionId immutable version identifier
     * @param grant one-use PNG authorization when the version is an image
     * @return fixed-format version download
     */
    public Download download(Principal principal, String id, String versionId, String grant) {
        requireReady();
        User owner = principal.user();
        Stored row = owned(owner, id);
        if (!AssistantArtifactImages.FORMAT.equals(row.summary().format())) {
            return renderedVersion(owner, row, versionId);
        }
        Version version = readableVersion(owner, row, versionId);
        byte[] bytes = attachments.downloadArtifact(principal, row.summary().projectId(),
            version.image().attachmentId(), versionId, grant);
        readableVersion(owner, owned(owner, id), versionId);
        return new Download(row.summary().format(), version.summary().number(), bytes, null);
    }

    /**
     * Issues a session authorization for this immutable approved image version only.
     *
     * @param principal authenticated scope and device session
     * @param id owned artifact identifier
     * @param versionId immutable image version identifier
     * @return one-use PNG authorization and its expiry
     */
    public DownloadGrant imageGrant(Principal principal, String id, String versionId) {
        requireReady();
        User owner = principal.user();
        Stored row = owned(owner, id);
        if (!AssistantArtifactImages.FORMAT.equals(row.summary().format())) {
            throw notFound();
        }
        var version = readableVersion(owner, row, versionId);
        var grant = attachments.grantArtifact(principal, row.summary().projectId(),
            version.image().attachmentId(), versionId);
        readableVersion(owner, owned(owner, id), versionId);
        return grant;
    }

    /**
     * Renders a single private webpage version under renewed owner and source authorization.
     *
     * @param owner authenticated scope
     * @param id owned webpage artifact identifier
     * @param versionId immutable version identifier
     * @return generated standalone UTF-8 HTML
     */
    public byte[] webpagePreview(User owner, String id, String versionId) {
        requireReady();
        Stored row = owned(owner, id);
        if (!AssistantWebpage.FORMAT.equals(row.summary().format())) {
            throw notFound();
        }
        return renderedVersion(owner, row, versionId).bytes();
    }

    private Download renderedVersion(User owner, Stored row, String versionId) {
        Version version = readableVersion(owner, row, versionId);
        Download file = renderBody(row.summary().format(), version);
        requireLive(owner, row);
        return file;
    }

    private static Download renderBody(String format, Version version) {
        byte[] bytes = switch (format) {
            case AssistantSpreadsheet.FORMAT -> AssistantSpreadsheet.xlsx(version.sheet());
            case AssistantPresentation.FORMAT -> AssistantPresentation.pptx(version.presentation());
            case AssistantCodeDraft.FORMAT ->
                version.code().source().getBytes(StandardCharsets.UTF_8);
            case AssistantWebpage.FORMAT -> AssistantWebpage.render(version.website());
            default -> version.content().getBytes(StandardCharsets.UTF_8);
        };
        return new Download(format, version.summary().number(), bytes,
            version.code() == null ? null : AssistantCodeDraft.fileSuffix(version.code()));
    }

    /**
     * Appends a version only when the authenticated caller observed the current head.
     *
     * @param owner authenticated scope
     * @param id artifact identifier
     * @param baseVersionId expected head
     * @param title requested title
     * @param input one matching content representation
     * @return new head
     */
    @Transactional
    public Artifact revise(User owner, String id, String baseVersionId, String title,
        WriteBody input) {
        Stored row = writable(owner, id, baseVersionId);
        String body = artifactBody(row.summary().format(), input);
        if (body == null) {
            throw invalid("Supply the content for the artifact version.");
        }
        return activate(owner, row, title(title), body, size(body), null);
    }

    /**
     * Copies one historical version into a fresh head, retaining both originals.
     *
     * @param owner authenticated scope
     * @param id artifact identifier
     * @param baseVersionId expected head
     * @param versionId chosen historical version
     * @return new head with rollback ancestry
     */
    @Transactional
    public Artifact rollback(User owner, String id, String baseVersionId, String versionId) {
        Stored row = writable(owner, id, baseVersionId);
        StoredVersion selected = validatedVersion(row.summary().id(), versionId);
        requireVersionLive(owner, row, selected);
        return activate(owner, row, selected.summary().title(), selected.content(),
            selected.summary().size(), versionId);
    }

    /**
     * Durably erases all versions and prevents recreating the same request identity after restoration.
     *
     * @param owner authenticated scope
     * @param id artifact identifier
     */
    @Transactional
    public void delete(User owner, String id) {
        requireReady();
        lockScope(owner);
        owned(owner, id);
        erasure.deleteArtifact(owner, id);
    }

    private Stored stored(User owner, String id) {
        return jdbc
            .query(ARTIFACTS + " AND a.id = ?", ARTIFACT_ROW, owner.id(), owner.workspaceId(), id)
            .stream().findFirst().orElse(null);
    }

    private Stored owned(User owner, String id) {
        requireId(id);
        Stored row = stored(owner, id);
        if (row == null) {
            throw notFound();
        }
        requireLive(owner, row);
        return row;
    }

    private Source source(User owner, String id, String messageId) {
        Source row = jdbc
            .query(SOURCE, (result, index) -> new Source(result.getString("conversation_id"),
                result.getString("project_id"), result.getString("content"),
                result.getString("read_lock_revision")), owner.id(),
                owner.workspaceId(), messageId)
            .stream().findFirst().orElseThrow(AssistantArtifacts::notFound);
        if (blocked(owner, id, row.conversationId())
            || !sourceOpen(owner.id(), owner.workspaceId(), row.conversationId(),
                row.readRevision())) {
            throw notFound();
        }
        return row;
    }

    private Stored writable(User owner, String id, String baseVersionId) {
        requireReady();
        requireId(baseVersionId);
        lockScope(owner);
        Stored row = owned(owner, id);
        requireFormatEditing(row.summary().format());
        if (!row.summary().headVersionId().equals(baseVersionId)) {
            throw conflict("The current artifact version changed.");
        }
        if (row.summary().headVersionNumber() >= MAX_VERSIONS) {
            throw conflict("The artifact has reached its version limit.");
        }
        return row;
    }

    private Artifact activate(User owner, Stored row, String title, String content, int size,
        String origin) {
        Summary summary = row.summary();
        String version = UUID.randomUUID().toString();
        long now = Math.max(System.currentTimeMillis(), summary.updatedAt().toEpochMilli());
        putVersion(summary.id(), version, summary.headVersionNumber() + 1, title, content, size,
            origin, now);
        bindImage(owner, summary.projectId(), summary.format(), summary.id(), version, content);
        int changed = jdbc.update("""
            UPDATE assistant_artifact SET head_version_id = ?, updated_at = ?
            WHERE id = ? AND account_id = ? AND workspace_id = ? AND head_version_id = ?
            """, version, now, summary.id(), owner.id(), owner.workspaceId(),
            summary.headVersionId());
        if (changed != 1) {
            throw conflict("The current artifact version changed.");
        }
        return artifact(owner, owned(owner, summary.id()));
    }

    private void putVersion(String artifact, String version, int number, String title,
        String content, int size,
        String ancestor, long now) {
        jdbc.update(
            """
                INSERT INTO assistant_artifact_version
                    (id, artifact_id, version_no, title, size_bytes, content, restored_from_version_id, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
            version, artifact, number, title, size, content, ancestor, now);
    }

    private Artifact artifact(User owner, Stored row) {
        return new Artifact(row.summary(),
            readableVersion(owner, row, row.summary().headVersionId()));
    }

    private void bindImage(User owner, String projectId, String format, String artifactId,
        String versionId, String content) {
        if (!AssistantArtifactImages.FORMAT.equals(format)) {
            return;
        }
        String attachmentId = AssistantArtifactImages.readStored(mapper, content).attachmentId();
        attachments.requireApprovedSource(owner, projectId, attachmentId);
        jdbc.update("""
            INSERT INTO assistant_artifact_image_source
                (workspace_id, account_id, project_id, artifact_id, version_id, attachment_id)
            VALUES (?, ?, ?, ?, ?, ?)
            """, owner.workspaceId(), owner.id(), projectId, artifactId, versionId, attachmentId);
    }

    private Version readableVersion(User owner, Stored row, String versionId) {
        StoredVersion storedVersion = validatedVersion(row.summary().id(), versionId);
        requireVersionLive(owner, row, storedVersion);
        Version version =
            Version.fromStorage(mapper, storedVersion.summary(), row.summary().format(),
                storedVersion.content(), storedVersion.imageSource());
        requireVersionLive(owner, row, storedVersion);
        return version;
    }

    private void requireVersionLive(User owner, Stored row, StoredVersion version) {
        synchronized (journal) {
            requireLive(owner, row);
            if (!imageVisible(owner, row.summary().format(), version.imageSource())) {
                throw notFound();
            }
        }
    }

    private boolean imageVisible(User owner, String format, AssistantArtifactImages.Source source) {
        return !AssistantArtifactImages.FORMAT.equals(format) || source != null
            && source.allows(journal, owner.id(), owner.workspaceId());
    }

    private Summary catalogSummary(User owner, Stored row) {
        Summary summary = row.summary();
        if (imageVisible(owner, summary.format(), row.imageSource())) {
            return summary;
        }
        return new Summary(summary.id(), summary.projectId(), null, summary.format(),
            summary.headVersionId(),
            summary.headVersionNumber(), "图片来源已撤回", 0, summary.createdAt(), summary.updatedAt(),
            false);
    }

    private StoredVersion validatedVersion(String id, String versionId) {
        requireId(versionId);
        StoredVersion version =
            jdbc.query(VERSION, VERSION_ROW, id, versionId).stream().findFirst()
                .orElseThrow(AssistantArtifacts::notFound);
        int actualSize;
        try {
            actualSize = size(version.content());
        } catch (AssistantApiException invalidStoredContent) {
            throw unavailable();
        }
        if (actualSize != version.summary().size()) {
            throw unavailable();
        }
        return version;
    }

    private void lockScope(User owner) {
        accounts.lockActive(owner.id());
        if (jdbc.queryForList(
            """
                SELECT account_id FROM assistant_workspace_member WHERE account_id = ? AND workspace_id = ? FOR UPDATE
                """,
            String.class, owner.id(), owner.workspaceId()).isEmpty()) {
            throw new AssistantApiException(HttpStatus.FORBIDDEN, "FORBIDDEN",
                "Workspace access is required.");
        }
    }

    private boolean blocked(User owner, String id, String conversation) {
        return journal.blocksArtifact(owner.id(), owner.workspaceId(), id, conversation);
    }

    private boolean blocksPublicationSource(String ownerId, String workspaceId, String artifactId,
        PublicationSource source) {
        return journal.blocksArtifact(ownerId, workspaceId, artifactId, source.conversationId())
            || !sourceOpen(ownerId, workspaceId, source.conversationId(), source.readRevision())
            || !source.placementCurrent(journal, ownerId, workspaceId)
            || journal.blocksArtifactPublicationPolicy(ownerId, workspaceId,
                source.conversationId(),
                source.policyRevision())
            || source.imageSource() != null
                && !source.imageSource().allows(journal, ownerId, workspaceId);
    }

    private void requireLive(User owner, Stored row) {
        requireReady();
        if (blocked(owner, row.summary().id(), row.conversationId())
            || !sourceOpen(owner.id(), owner.workspaceId(), row.conversationId(),
                row.readRevision())) {
            throw notFound();
        }
    }

    private boolean sourceOpen(String ownerId, String workspaceId, String conversationId,
        String revision) {
        return conversationId == null
            || !journal.blocksConversationRead(ownerId, workspaceId, conversationId, revision);
    }

    private void requireReady() {
        if (!enabled || !journal.enabled()) {
            throw new AssistantApiException(HttpStatus.FORBIDDEN, "CAPABILITY_UNAVAILABLE",
                "Private artifacts are unavailable.");
        }
        if (!journal.acceptsErasure()) {
            throw unavailable();
        }
    }

    private void requireFormatEditing(String format) {
        if (AssistantSpreadsheet.FORMAT.equals(format) && !spreadsheetsEnabled
            || AssistantPresentation.FORMAT.equals(format) && !slidesEnabled
            || AssistantCodeDraft.FORMAT.equals(format) && !codeEnabled
            || AssistantWebpage.FORMAT.equals(format) && !webpagesEnabled
            || AssistantArtifactImages.FORMAT.equals(format) && !imageEditingAvailable()) {
            throw new AssistantApiException(HttpStatus.FORBIDDEN, "CAPABILITY_UNAVAILABLE",
                "Private editing is unavailable for this artifact format.");
        }
    }

    private String artifactBody(String format, WriteBody input) {
        if (input.content() != null && structured(format)
            || input.sheet() != null && !AssistantSpreadsheet.FORMAT.equals(format)
            || input.presentation() != null && !AssistantPresentation.FORMAT.equals(format)
            || input.code() != null && !AssistantCodeDraft.FORMAT.equals(format)
            || input.website() != null && !AssistantWebpage.FORMAT.equals(format)
            || input.image() != null && !AssistantArtifactImages.FORMAT.equals(format)) {
            throw invalid("Supply content that matches the artifact's format.");
        }
        return switch (format) {
            case AssistantSpreadsheet.FORMAT ->
                AssistantSpreadsheet.encode(mapper, AssistantSpreadsheet.input(input.sheet()));
            case AssistantPresentation.FORMAT ->
                AssistantPresentation.encode(mapper,
                    AssistantPresentation.input(input.presentation()));
            case AssistantCodeDraft.FORMAT ->
                AssistantCodeDraft.encode(mapper, AssistantCodeDraft.input(input.code()));
            case AssistantWebpage.FORMAT ->
                AssistantWebpage.encode(mapper, AssistantWebpage.input(input.website()));
            case AssistantArtifactImages.FORMAT ->
                AssistantArtifactImages.encode(mapper,
                    AssistantArtifactImages.input(input.image()));
            default -> input.content();
        };
    }

    private static boolean structured(String format) {
        return typedReplyFormat(format) || AssistantWebpage.FORMAT.equals(format);
    }

    private static boolean typedReplyFormat(String format) {
        return AssistantSpreadsheet.FORMAT.equals(format)
            || AssistantPresentation.FORMAT.equals(format)
            || AssistantCodeDraft.FORMAT.equals(format)
            || AssistantArtifactImages.FORMAT.equals(format);
    }

    static ObjectReader jsonReader(ObjectMapper mapper) {
        return mapper.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS,
            DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    private static VersionSummary versionSummary(ResultSet row) throws SQLException {
        return new VersionSummary(row.getString("id"), row.getString("artifact_id"),
            row.getInt("version_no"),
            row.getString("title"), row.getInt("size_bytes"), instant(row, "created_at"),
            row.getString("restored_from_version_id"));
    }

    private static PublicationSource readPublicationSource(ResultSet row, int index)
        throws SQLException {
        String format = row.getString("format");
        var image = AssistantArtifactImages.FORMAT.equals(format)
            ? AssistantArtifactImages.source(row) : null;
        int size = image == null ? row.getInt("size_bytes") : image.size();
        return new PublicationSource(row.getString("title"), format,
            row.getInt("version_no"), size, row.getString("policy_revision"),
            row.getString("source_conversation_id"), row.getString("read_lock_revision"), null,
            image, row.getString("source_placement_revision"));
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        return Instant.ofEpochMilli(row.getLong(column));
    }

    static String identity(User owner, String key) {
        byte[] id = digest("assistant-private-artifact/v1", owner.id(), owner.workspaceId(), key);
        id[6] = (byte) ((id[6] & 0x0f) | 0x80);
        id[8] = (byte) ((id[8] & 0x3f) | 0x80);
        var buffer = ByteBuffer.wrap(id);
        return new UUID(buffer.getLong(), buffer.getLong()).toString();
    }

    private static byte[] digest(String... parts) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            for (String part : parts) {
                byte[] input = part == null ? new byte[0] : part.getBytes(StandardCharsets.UTF_8);
                sha.update(ByteBuffer.allocate(Integer.BYTES)
                    .putInt(part == null ? -1 : input.length).array());
                sha.update(input);
            }
            return sha.digest();
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("Private artifact identity is unavailable.", failure);
        }
    }

    private static String title(String title) {
        if (title == null || title.length() > MAX_TITLE * 2 || title.isBlank()
            || title.codePointCount(0, title.length()) > MAX_TITLE
            || !StandardCharsets.UTF_8.newEncoder().canEncode(title)
            || title.codePoints().anyMatch(Character::isISOControl)) {
            throw invalid("An artifact title must contain 1 to 120 visible characters.");
        }
        return title.strip();
    }

    static int size(String content) {
        if (content == null || content.length() > MAX_BYTES) {
            throw tooLarge();
        }
        if (!StandardCharsets.UTF_8.newEncoder().canEncode(content)
            || content.codePoints().anyMatch(point -> Character.isISOControl(point)
                && point != '\t' && point != '\r' && point != '\n')) {
            throw invalid("Artifact content must be valid plain UTF-8 text.");
        }
        int length = content.getBytes(StandardCharsets.UTF_8).length;
        if (length > MAX_BYTES) {
            throw tooLarge();
        }
        return length;
    }

    private static void requireFormat(String value) {
        if (!"text".equals(value) && !"markdown".equals(value)
            && !structured(value)) {
            throw invalid("Choose a supported private artifact format.");
        }
    }

    private static int pageSize(Integer limit) {
        if (limit == null) {
            return 20;
        }
        if (limit < 1 || limit > 40) {
            throw invalid("Choose a page limit from 1 to 40.");
        }
        return limit;
    }

    private static int versionNumber(String value) {
        try {
            int number = Integer.parseInt(value);
            if (number >= 1 && number <= MAX_VERSIONS && Integer.toString(number).equals(value)) {
                return number;
            }
        } catch (NumberFormatException ignored) {
            // An invalid anchor has the same response as an unscoped cursor.
        }
        throw invalid("The artifact cursor is invalid.");
    }

    private static void requireId(String id) {
        try {
            if (id != null && id.length() == 36 && UUID.fromString(id).toString().equals(id)) {
                return;
            }
        } catch (IllegalArgumentException ignored) {
            // All malformed identity representations receive the same error.
        }
        throw invalid("A valid artifact, project, reply or version UUID is required.");
    }

    private static String cursor(User owner, String section, String last) {
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString((cursorScope(owner, section) + last)
                .getBytes(StandardCharsets.US_ASCII));
    }

    private static String readCursor(User owner, String section, String cursor) {
        if (!cursor.isEmpty() && cursor.length() <= 224) {
            try {
                String decoded =
                    new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.US_ASCII);
                String prefix = cursorScope(owner, section);
                if (decoded.startsWith(prefix)) {
                    return decoded.substring(prefix.length());
                }
            } catch (IllegalArgumentException ignored) {
                // Do not echo a forged cursor.
            }
        }
        throw invalid("The artifact cursor is invalid for this scope.");
    }

    private static String cursorScope(User owner, String section) {
        return "1:" + owner.id() + ":" + owner.workspaceId() + ":" + section + ":";
    }

    private static AssistantApiException notFound() {
        return new AssistantApiException(HttpStatus.NOT_FOUND, "NOT_FOUND",
            "Private artifact, source reply or version not found.");
    }

    private static AssistantApiException invalid(String detail) {
        return new AssistantApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", detail);
    }

    private static AssistantApiException conflict(String detail) {
        return new AssistantApiException(HttpStatus.CONFLICT, "CONFLICT", detail);
    }

    private static AssistantApiException tooLarge() {
        return new AssistantApiException(HttpStatus.PAYLOAD_TOO_LARGE, "VALIDATION_ERROR",
            "Artifact content exceeds 32 KiB of UTF-8 text.");
    }

    private static AssistantApiException unavailable() {
        return new AssistantApiException(HttpStatus.SERVICE_UNAVAILABLE, "CAPABILITY_UNAVAILABLE",
            "Private artifacts are temporarily unavailable.");
    }

    /**
     * New artifact input and optionally selected owner reply.
     *
     * @param projectId optional fixed project identifier
     * @param sourceMessageId optional completed reply identifier
     * @param title version title
     * @param format selected text or structured artifact format
     * @param body one representation, empty only to seed from an owned reply
     * @param confirmSource explicit opt-in for linking this typed reviewed body to a completed reply
     */
    public record Create(String projectId, String sourceMessageId, String title, String format,
        WriteBody body, Boolean confirmSource) {

        @Override
        public String toString() {
            return "Create[protected]";
        }
    }

    /**
     * Private HTTP write input before format-specific validation.
     *
     * @param content exact plain text or Markdown
     * @param sheet typed spreadsheet grid
     * @param presentation ordered text slides
     * @param code literal source and language
     * @param website structured text webpage
     * @param image selection of an approved project PNG with alternative text
     */
    public record WriteBody(String content, JsonNode sheet, JsonNode presentation, JsonNode code,
        JsonNode website, JsonNode image) {

        /**
         * Counts supplied content fields for the exact JSON shape check.
         *
         * @return number of provided representations
         */
        public int fieldCount() {
            return (content == null ? 0 : 1) + (sheet == null ? 0 : 1)
                + (presentation == null ? 0 : 1)
                + (code == null ? 0 : 1) + (website == null ? 0 : 1) + (image == null ? 0 : 1);
        }

        @Override
        public String toString() {
            return "WriteBody[protected]";
        }
    }

    /**
     * Metadata excluding all draft body and storage details.
     *
     * @param id private artifact identifier
     * @param projectId associated owned project if any
     * @param sourceMessageId associated completed reply if any
     * @param format selected text or structured artifact format
     * @param headVersionId current immutable version identifier
     * @param headVersionNumber current version number
     * @param title current version title
     * @param size current content length in UTF-8 bytes
     * @param createdAt original creation
     * @param updatedAt last successful head change
     * @param headAvailable false for an owned image whose source is withheld
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Summary(String id, String projectId, String sourceMessageId, String format,
        String headVersionId, int headVersionNumber, String title, int size, Instant createdAt,
        Instant updatedAt, Boolean headAvailable) {

        @Override
        public String toString() {
            return "Summary[protected]";
        }
    }

    /**
     * An owned artifact and its current head.
     *
     * @param summary metadata mapped at the top level
     * @param head current immutable version and text
     */
    public record Artifact(@JsonUnwrapped Summary summary, Version head) {
    }

    /**
     * Portable metadata for one immutable version.
     *
     * @param id immutable version identifier
     * @param artifactId owning artifact
     * @param number sequential version number within the artifact
     * @param title captured version title
     * @param size captured UTF-8 byte length
     * @param createdAt time this version was accepted
     * @param restoredFromVersionId historical version intentionally restored if any
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record VersionSummary(String id, String artifactId, int number, String title, int size,
        Instant createdAt, String restoredFromVersionId) {

        @Override
        public String toString() {
            return "VersionSummary[protected]";
        }
    }

    /**
     * Immutable version with its exact original text.
     *
     * @param summary immutable metadata mapped at the top level
     * @param content exact textual body for a text artifact
     * @param sheet exact typed grid for a spreadsheet
     * @param presentation exact text slides for a presentation
     * @param code literal code source and language
     * @param website structured text webpage
     * @param image approved source metadata and alternative text
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Version(@JsonUnwrapped VersionSummary summary, String content,
        AssistantSpreadsheet.Sheet sheet, AssistantPresentation.Presentation presentation,
        AssistantCodeDraft.Code code, AssistantWebpage.Website website,
        AssistantArtifactImages.Image image) {

        /**
         * Creates a portable version from validated owned storage.
         *
         * @param mapper application JSON codec
         * @param summary exact immutable version metadata
         * @param format declared format from the version's authorized artifact
         * @param body exact text or canonical structured representation
         * @param imageSource current approved project PNG projection for an image version
         * @return an owner-facing version with the corresponding content type
         */
        public static Version fromStorage(ObjectMapper mapper, VersionSummary summary,
            String format, String body, AssistantArtifactImages.Source imageSource) {
            if (AssistantSpreadsheet.FORMAT.equals(format)) {
                return new Version(summary, null, AssistantSpreadsheet.readStored(mapper, body),
                    null, null, null, null);
            }
            if (AssistantPresentation.FORMAT.equals(format)) {
                return new Version(summary, null, null,
                    AssistantPresentation.readStored(mapper, body), null, null, null);
            }
            if (AssistantCodeDraft.FORMAT.equals(format)) {
                return new Version(summary, null, null, null,
                    AssistantCodeDraft.readStored(mapper, body), null, null);
            }
            if (AssistantWebpage.FORMAT.equals(format)) {
                return new Version(summary, null, null, null, null,
                    AssistantWebpage.readStored(mapper, body), null);
            }
            if (AssistantArtifactImages.FORMAT.equals(format)) {
                return new Version(summary, null, null, null, null, null,
                    AssistantArtifactImages.image(mapper, body, imageSource));
            }
            return new Version(summary, body, null, null, null, null, null);
        }

        @Override
        public String toString() {
            return "Version[protected]";
        }
    }

    /**
     * Page of catalog or version metadata with explicit completion.
     *
     * @param items bounded visible page
     * @param nextCursor continuation or null when complete
     * @param <T> artifact or version metadata
     */
    public record Page<T>(List<T> items,
        @JsonInclude(JsonInclude.Include.ALWAYS) String nextCursor) {
    }

    /**
     * Validated body and format for a protected response attachment.
     *
     * @param format permitted text or Office MIME selection
     * @param version sequential identifier used in the fixed filename
     * @param bytes exact text or generated Office attachment contents
     * @param codeSuffix fixed literal filename suffix for a code draft
     */
    public record Download(String format, int version, byte[] bytes, String codeSuffix) {
    }

    /**
     * Internal projection for the purpose-built collaboration authorization path.
     *
     * @param title immutable version title
     * @param format immutable artifact format
     * @param versionNumber immutable version ordinal
     * @param sizeBytes exact UTF-8 size or approved PNG byte length
     * @param policyRevision source sharing policy revision
     * @param conversationId private linked source, used only for lineage checks
     * @param readRevision private source read generation
     * @param body validated original format data when requested
     * @param imageSource internal approved PNG address and validated dimensions
     * @param sourcePlacementRevision linked typed reply placement at author confirmation
     */
    public record PublicationSource(String title, String format, int versionNumber, int sizeBytes,
        String policyRevision, String conversationId, String readRevision, @JsonIgnore Version body,
        @JsonIgnore AssistantArtifactImages.Source imageSource, String sourcePlacementRevision) {

        private PublicationSource withBody(ObjectMapper mapper, StoredVersion stored) {
            var validated = Version.fromStorage(mapper, stored.summary(), format, stored.content(),
                imageSource);
            return new PublicationSource(title, format, versionNumber, sizeBytes, policyRevision,
                conversationId, readRevision, validated, imageSource, sourcePlacementRevision);
        }

        /**
         * Checks that a typed reply's project placement still matches the owner's confirmation.
         *
         * @param journal authoritative placement markers
         * @param ownerId current authorized source owner
         * @param workspaceId current authorized source workspace
         * @return true for an unchanged placement or an artifact independent of typed reply placement
         */
        public boolean placementCurrent(AssistantDeletionJournal journal, String ownerId,
            String workspaceId) {
            return conversationId == null || !typedReplyFormat(format)
                || Objects.equals(sourcePlacementRevision,
                    journal.historyPlacementRevision(ownerId, workspaceId, conversationId));
        }

        /**
         * Exposes only the deliberate image description and approved dimensions to project members.
         *
         * @return image metadata when this publication contains a PNG
         */
        public AssistantArtifactImages.SharedImage image() {
            return body.image() == null ? null : body.image().shared();
        }

        /**
         * Produces the same validated Office or literal text export as the owner's version.
         *
         * @return bounded export with the format and safe source suffix
         */
        public Download rendered() {
            if (AssistantArtifactImages.FORMAT.equals(format)) {
                throw notFound();
            }
            return renderBody(format, body);
        }

        /**
         * Renders a previously authorized structured webpage with the private artifact template.
         *
         * @return fixed-template HTML bytes
         */
        public byte[] renderedWebsite() {
            return AssistantWebpage.render(Objects.requireNonNull(body.website()));
        }

        /**
         * Returns the single fixed template policy used by private and shared webpage downloads.
         *
         * @return enforced HTTP content security policy
         */
        public static String webpageResponsePolicy() {
            return AssistantWebpage.RESPONSE_POLICY;
        }

        @Override
        public String toString() {
            return "PublicationSource[protected]";
        }
    }

    private record Stored(Summary summary, String conversationId, String readRevision,
        String requestDigest, AssistantArtifactImages.Source imageSource) {
    }

    private record StoredVersion(VersionSummary summary, String content,
        AssistantArtifactImages.Source imageSource) {

        @Override
        public String toString() {
            return "StoredVersion[protected]";
        }
    }

    private record Source(String conversationId, String projectId, String content,
        String readRevision) {

        @Override
        public String toString() {
            return "Source[protected]";
        }
    }
}

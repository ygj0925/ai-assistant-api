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

package top.continew.admin.assistant.attachment;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.core.exception.SdkException;
import top.continew.admin.assistant.account.AssistantAccounts;
import top.continew.admin.assistant.account.AssistantAccounts.Principal;
import top.continew.admin.assistant.account.AssistantAccounts.User;
import top.continew.admin.assistant.account.AssistantApiException;
import top.continew.admin.assistant.attachment.AttachmentImageEditProof.Origin;
import top.continew.admin.assistant.attachment.AttachmentImageEditProof.Proof;
import top.continew.admin.assistant.attachment.AttachmentPngTransform.Edit;
import top.continew.admin.assistant.privacy.AssistantDeletionJournal;
import top.continew.admin.assistant.privacy.AssistantErasure;
import top.continew.admin.assistant.project.AssistantProjects;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Quarantines, validates and serves scoped private project attachments.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
@Service
@ConditionalOnProperty(name = "assistant.enabled", havingValue = "true")
public class AssistantAttachments {

    private static final Logger LOG = LoggerFactory.getLogger(AssistantAttachments.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern DOWNLOAD_TOKEN = Pattern.compile("[A-Za-z0-9_-]{43}");
    private static final long GRANT_MILLIS = 60_000;
    private static final String ORIGINAL_SCOPE = "attachment";
    private static final String RECEIVING = "receiving";
    private static final String QUARANTINED = "quarantined";
    private static final String APPROVED = "approved";
    private static final String SELECT =
        """
            SELECT a.id, a.account_id, a.workspace_id, a.project_id, a.status, a.size_bytes, a.width, a.height,
                a.original_digest, a.approved_digest, a.error_code, a.scan_attempts, a.created_at,
                a.media_kind, a.sample_rate, a.channel_count, a.duration_millis, a.pdf_ocr,
            """
            + AttachmentImageEditProof.PROJECTION + " FROM assistant_attachment a "
            + AttachmentImageEditProof.joins("a");
    private static final String OWNER_SCOPE = """
        WHERE a.project_id = ? AND a.workspace_id = ? AND a.account_id = ?
        """;
    private static final String OWNED = OWNER_SCOPE + " AND a.status <> 'revoked'";
    private static final RowMapper<Stored> ATTACHMENT = (row, index) -> new Stored(
        row.getString("id"), row.getString("account_id"), row.getString("workspace_id"),
        row.getString("project_id"), row.getString("status"), row.getInt("size_bytes"),
        row.getInt("width"), row.getInt("height"), row.getString("original_digest"),
        row.getString("approved_digest"), row.getString("error_code"), row.getInt("scan_attempts"),
        row.getLong("created_at"), Kind.valueOf(row.getString("media_kind")),
        row.getInt("sample_rate"), row.getInt("channel_count"), row.getInt("duration_millis"),
        AttachmentImageEditProof.read(row), row.getBoolean("pdf_ocr"));
    private static final RowMapper<Cleanup> CLEANUP =
        (row, index) -> new Cleanup(row.getString("event_id"),
            row.getString("account_id"), row.getString("workspace_id"),
            row.getString("attachment_id"));

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final AssistantAccounts accounts;
    private final AssistantProjects projects;
    private final AssistantDeletionJournal journal;
    private final AssistantErasure erasure;
    private final AttachmentObjects objects;
    private final AttachmentScanner scanner;
    private final boolean enabled;
    private final boolean audioEnabled;
    private final boolean pdfEnabled;
    private final boolean pdfOcrEnabled;
    private final AttachmentPdfExtractor pdfExtractor;
    private final boolean localImageEditsEnabled;
    private final Semaphore editCapacity = new Semaphore(1);

    /**
     * Configures the independent storage and scanner with explicit local credentials.
     *
     * @param jdbc assistant data access
     * @param transactionManager transaction manager for durable upload reservation
     * @param accounts account lifecycle
     * @param projects project ownership
     * @param journal durable deletion intents
     * @param erasure scoped attachment deletion
     * @param enabled binary attachment policy
     * @param audioEnabled opt-in private PCM WAVE intake
     * @param pdfEnabled opt-in local PDF text intake
     * @param pdfOcrEnabled opt-in to approximated OCR output from the isolated extractor
     * @param pdfExtractorUrl isolated loopback PDF extractor endpoint
     * @param pdfExtractorKey dedicated credential for the PDF extractor
     * @param localImageEditsEnabled opt-in local PNG crop and rotation
     * @param environment active deployment profiles
     * @param objectEndpoint explicitly provisioned private S3 endpoint
     * @param bucket private S3 bucket
     * @param accessKey scoped object store key
     * @param secretKey scoped object store secret
     * @param scannerPort isolated loopback ClamD port, or zero when unconfigured
     */
    public AssistantAttachments(JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
        AssistantAccounts accounts, AssistantProjects projects, AssistantDeletionJournal journal,
        AssistantErasure erasure, @Value("${assistant.attachments.enabled:false}") boolean enabled,
        @Value("${assistant.attachments.audio-enabled:false}") boolean audioEnabled,
        @Value("${assistant.attachments.pdf-enabled:false}") boolean pdfEnabled,
        @Value("${assistant.attachments.pdf-ocr-enabled:false}") boolean pdfOcrEnabled,
        @Value("${assistant.attachments.pdf-extractor-url:}") String pdfExtractorUrl,
        @Value("${assistant.attachments.pdf-extractor-key:}") String pdfExtractorKey,
        @Value("${assistant.attachments.local-image-edits-enabled:false}") boolean localImageEditsEnabled,
        Environment environment,
        @Value("${assistant.attachments.object-endpoint:}") String objectEndpoint,
        @Value("${assistant.attachments.bucket:}") String bucket,
        @Value("${assistant.attachments.access-key:}") String accessKey,
        @Value("${assistant.attachments.secret-key:}") String secretKey,
        @Value("${assistant.attachments.scanner-port:0}") int scannerPort) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setTimeout(20);
        this.accounts = accounts;
        this.projects = projects;
        this.journal = journal;
        this.erasure = erasure;
        this.enabled = enabled;
        this.audioEnabled = audioEnabled;
        boolean localProfile = environment.matchesProfiles("(dev | test) & !prod");
        this.pdfEnabled = pdfEnabled && localProfile;
        this.pdfOcrEnabled = pdfOcrEnabled && this.pdfEnabled;
        this.pdfExtractor =
            this.pdfEnabled && (!pdfExtractorUrl.isBlank() || !pdfExtractorKey.isBlank())
                ? new AttachmentPdfExtractor(pdfExtractorUrl, pdfExtractorKey) : null;
        this.localImageEditsEnabled = localImageEditsEnabled && localProfile;
        this.objects = objectEndpoint.isBlank() ? null
            : new AttachmentObjects(objectEndpoint, bucket, accessKey, secretKey);
        this.scanner = scannerPort == 0 ? null : new AttachmentScanner(scannerPort);
    }

    /**
     * Reports whether attachment intake and revocation are configured.
     *
     * @return whether this deployment enables the capability
     */
    public boolean available() {
        return enabled && objects != null && scanner != null && journal.acceptsErasure();
    }

    /**
     * Reports whether the explicit private WAVE upload workflow is active.
     *
     * @return true if upload and scanning are enabled
     */
    public boolean audioUploadAvailable() {
        return audioEnabled && available();
    }

    /**
     * Keeps audio management accessible for scoped existing records while intake is paused.
     *
     * @param owner authenticated owner
     * @return whether to expose the audio list and download workflow
     */
    public boolean audioManagementAvailable(User owner) {
        return available() && (audioEnabled || hasManagedAttachment(owner, Kind.WAVE));
    }

    /**
     * Reports whether local PDF text intake and access are enabled.
     *
     * @return whether this deployment offers the bounded PDF text workflow
     */
    public boolean pdfUploadAvailable() {
        return pdfEnabled && available();
    }

    /**
     * Reports whether scan-approved PDFs can use the independent text extractor.
     *
     * @return whether the bounded extended input profile is configured
     */
    public boolean generalPdfAvailable() {
        return pdfUploadAvailable() && pdfExtractor != null;
    }

    /**
     * Indicates that newly uploaded documents may use the local approximate OCR profile.
     *
     * @return true when this deployment has opted in to receiving OCR output
     */
    public boolean pdfOcrAvailable() {
        return pdfOcrEnabled && generalPdfAvailable();
    }

    /**
     * Keeps metadata and deletion available for existing PDF attachments when intake is paused.
     *
     * @param owner authenticated owner
     * @return whether to expose PDF attachment management
     */
    public boolean pdfManagementAvailable(User owner) {
        return available() && (pdfEnabled || hasManagedAttachment(owner, Kind.PDF));
    }

    /**
     * Reports whether this deployment offers local crop and quarter-turn on an approved PNG.
     *
     * @return whether local edits can be requested
     */
    public boolean localImageEditsAvailable() {
        return localImageEditsEnabled && available();
    }

    /**
     * Binds a confirmed crop to a current original and quarantines the deterministic PNG.
     *
     * @param principal authenticated device and owner
     * @param projectId owned project
     * @param originalId original approved PNG in the same project
     * @param key client retry UUID
     * @param geometry reviewed crop and rotation
     * @return current quarantine or approved state
     */
    Attachment localImageEdit(Principal principal, String projectId, String originalId, String key,
        Edit geometry) {
        requireReady();
        if (!localImageEditsEnabled) {
            throw new AssistantApiException(HttpStatus.FORBIDDEN, "CAPABILITY_UNAVAILABLE",
                "Local image edits are unavailable.");
        }
        requireId(key);
        if (!editCapacity.tryAcquire()) {
            throw new AssistantApiException(HttpStatus.TOO_MANY_REQUESTS, "LOCAL_IMAGE_EDIT_BUSY",
                "A local image edit is running. Retry shortly using the same request key.");
        }
        try {
            User owner = principal.user();
            Stored original = originalPng(owner, projectId, originalId);
            var origin = new Origin("local_image_edit_v1", originalId, geometry.x(), geometry.y(),
                geometry.width(), geometry.height(), geometry.rotation());
            var request =
                new ImageEditRequest(principal.sessionId(), origin, membership(owner, false),
                    original.approvedDigest());
            Stored previous = findUpload(owner, projectId, key);
            if (previous != null) {
                requireMatchingUpload(previous, Kind.EDIT, request, null);
                if (!previous.is(RECEIVING)) {
                    return previous.snapshot();
                }
            } else {
                requireUploadSpace(owner, projectId);
            }
            byte[] source = readApproved(original, () -> originalPng(owner, projectId, originalId));
            byte[] output = AttachmentPngTransform.render(source, geometry);
            return upload(owner, projectId, key, output, Kind.EDIT, request);
        } catch (IllegalArgumentException failure) {
            throw new AssistantApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                "Choose an in-bounds crop and clockwise quarter-turn of an original PNG up to 480 pixels per side.");
        } catch (IOException failure) {
            throw unavailable();
        } finally {
            editCapacity.release();
        }
    }

    private Stored originalPng(User owner, String projectId, String id) {
        Stored original = approved(owner, projectId, id, Kind.PNG);
        if (original.kind() != Kind.PNG || original.editProof() != null) {
            throw new AssistantApiException(HttpStatus.CONFLICT, "CONFLICT",
                "Choose an approved original PNG as the source.");
        }
        if (original.approvedDigest() == null) {
            throw notFound();
        }
        return original;
    }

    private String membership(User owner, boolean lock) {
        String query = """
            SELECT membership_revision FROM assistant_workspace_member
            WHERE account_id = ? AND workspace_id = ?
            """;
        String revision = jdbc.queryForList(lock ? query + " FOR UPDATE" : query, String.class,
            owner.id(), owner.workspaceId()).stream().findFirst()
            .orElseThrow(AssistantAttachments::notFound);
        if (journal.blocksWorkspaceMembership(owner.id(), owner.workspaceId(), revision)) {
            throw notFound();
        }
        return revision;
    }

    private void recheckImageEdit(User owner, String projectId, ImageEditRequest edit) {
        if (edit == null) {
            return;
        }
        if (jdbc.queryForList("""
            SELECT id FROM assistant_session
            WHERE id = ? AND account_id = ? AND expires_at > ? FOR UPDATE
            """, String.class, edit.sessionId(), owner.id(), System.currentTimeMillis())
            .isEmpty()) {
            throw new AssistantApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED",
                "Sign in to continue the image edit.");
        }
        if (!edit.membershipRevision().equals(membership(owner, true))
            || !edit.sourceDigest().equals(
                originalPng(owner, projectId, edit.origin().sourceAttachmentId())
                    .approvedDigest())) {
            throw notFound();
        }
    }

    private boolean hasManagedAttachment(User owner, Kind kind) {
        return owner != null && !jdbc.queryForList("""
            SELECT id FROM assistant_attachment
            WHERE account_id = ? AND workspace_id = ? AND media_kind = ? AND status <> 'revoked'
            LIMIT 1
            """, String.class, owner.id(), owner.workspaceId(), kind.name()).isEmpty();
    }

    /**
     * Establishes a durable upload identity and commits the complete private quarantine object.
     *
     * @param owner authenticated workspace owner
     * @param projectId destination project
     * @param key client retry identifier
     * @param content bounded raw upload
     * @param kind validated request media type
     * @return current attachment state
     */
    Attachment upload(User owner, String projectId, String key, byte[] content, Kind kind) {
        return upload(owner, projectId, key, content, kind, null);
    }

    private Attachment upload(User owner, String projectId, String key, byte[] content, Kind kind,
        ImageEditRequest edit) {
        requireUploadReady(kind);
        requireId(key);
        MediaInfo media;
        try {
            media = kind.inspect(content, pdfExtractor != null);
        } catch (IllegalArgumentException failure) {
            throw new AssistantApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                kind == Kind.PDF && pdfExtractor != null ? "Upload a PDF within 32 KiB."
                    : kind.invalidInputMessage());
        }
        String fingerprint = digest(content);
        Stored reserved = transactions.execute(status -> {
            accounts.lockActive(owner.id());
            projects.requireOwned(owner, projectId);
            recheckImageEdit(owner, projectId, edit);
            Stored existing = findUpload(owner, projectId, key);
            if (existing != null) {
                requireMatchingUpload(existing, kind, edit, fingerprint);
                return existing;
            }
            requireUploadSpace(owner, projectId);
            String id = uploadId(owner, projectId, key, kind);
            if (journal.blocksAttachment(owner.id(), owner.workspaceId(), id)) {
                throw notFound();
            }
            long now = System.currentTimeMillis();
            jdbc.update("""
                INSERT INTO assistant_attachment
                    (id, account_id, workspace_id, project_id, idempotency_key, original_digest,
                     status, size_bytes, width, height, due_at, created_at, updated_at,
                     media_kind, sample_rate, channel_count, duration_millis)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, owner.id(), owner.workspaceId(), projectId, key, fingerprint,
                kind.status(RECEIVING), content.length, media.width(), media.height(),
                now + 600_000, now, now, kind.name(), media.sampleRate(), media.channels(),
                media.durationMillis());
            if (edit != null) {
                putImageEdit(owner, projectId, id, edit);
            }
            return scoped(owner, projectId, id, kind);
        });
        if (!reserved.is(RECEIVING)) {
            return reserved.snapshot();
        }
        try {
            return transactions.execute(status -> {
                accounts.lockActive(owner.id());
                recheckImageEdit(owner, projectId, edit);
                Stored row = scoped(owner, projectId, reserved.id(), kind);
                if (row.is(RECEIVING)) {
                    objects.put(row.key(false), content);
                    long now = System.currentTimeMillis();
                    jdbc.update(
                        """
                            UPDATE assistant_attachment SET status = ?, due_at = ?, updated_at = ?
                            WHERE id = ?
                            """,
                        kind.status(QUARANTINED), now, now, row.id());
                }
                return scoped(owner, projectId, row.id(), kind).snapshot();
            });
        } catch (SdkException failure) {
            throw unavailable();
        }
    }

    private Stored findUpload(User owner, String projectId, String key) {
        return jdbc.query(SELECT + OWNER_SCOPE + " AND a.idempotency_key = ?", ATTACHMENT,
            projectId, owner.workspaceId(), owner.id(), key).stream().findFirst().orElse(null);
    }

    private void requireMatchingUpload(Stored row, Kind kind, ImageEditRequest edit,
        String digest) {
        if ("revoked".equals(row.status()) || blocked(row)) {
            throw notFound();
        }
        boolean matchingEdit =
            edit == null ? row.editProof() == null : edit.matches(row.editProof());
        if (row.kind() != kind || !matchingEdit
            || digest != null && !digest.equals(row.originalDigest())) {
            throw new AssistantApiException(HttpStatus.CONFLICT, "CONFLICT",
                "This request key already belongs to different content.");
        }
    }

    private void requireUploadSpace(User owner, String projectId) {
        long owned = count("WHERE a.account_id = ? AND a.status <> 'revoked'", owner.id());
        long inProject = count(OWNED, projectId, owner.workspaceId(), owner.id());
        if (owned >= 32 || inProject >= 8) {
            throw new AssistantApiException(HttpStatus.CONFLICT, "CONFLICT",
                "The project or account attachment quota is reached.");
        }
    }

    private void putImageEdit(User owner, String projectId, String id, ImageEditRequest edit) {
        Origin origin = edit.origin();
        jdbc.update("""
            INSERT INTO assistant_attachment_image_edit
                (output_attachment_id, source_attachment_id, account_id, workspace_id, project_id,
                    membership_revision, source_digest, algorithm, crop_x, crop_y, crop_width,
                    crop_height, rotation)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, id, origin.sourceAttachmentId(), owner.id(), owner.workspaceId(), projectId,
            edit.membershipRevision(), edit.sourceDigest(), origin.algorithm(), origin.x(),
            origin.y(), origin.width(), origin.height(), origin.rotation());
    }

    /**
     * Lists visible attachment metadata in the current project.
     *
     * @param owner authenticated owner
     * @param projectId owned project
     * @param kind requested media type
     * @return at most eight items
     */
    List<Attachment> list(User owner, String projectId, Kind kind) {
        requireReady();
        projects.requireOwned(owner, projectId);
        return jdbc
            .query(
                SELECT + OWNED + kind.routeScope()
                    + " ORDER BY a.created_at DESC, a.id DESC LIMIT 8",
                ATTACHMENT, projectId, owner.workspaceId(), owner.id())
            .stream().filter(row -> !blocked(row))
            .map(Stored::snapshot).toList();
    }

    /**
     * Reads visible status without returning quarantined bytes or storage URLs.
     *
     * @param owner authenticated owner
     * @param projectId owned project
     * @param attachmentId requested attachment
     * @param kind requested media type
     * @return current status
     */
    Attachment get(User owner, String projectId, String attachmentId, Kind kind) {
        requireReady();
        return scoped(owner, projectId, attachmentId, kind).snapshot();
    }

    /**
     * Cancels or removes a quarantined or completed attachment.
     *
     * @param owner authenticated owner
     * @param projectId owned project
     * @param attachmentId attachment to remove
     * @param kind requested media type
     */
    void delete(User owner, String projectId, String attachmentId, Kind kind) {
        requireReady();
        requireId(attachmentId);
        transactions.executeWithoutResult(status -> {
            accounts.lockActive(owner.id());
            projects.requireOwned(owner, projectId);
            if (jdbc.queryForList("""
                SELECT a.id FROM assistant_attachment a
                WHERE a.project_id = ? AND a.workspace_id = ? AND a.account_id = ? AND a.id = ?
                """ + kind.routeScope(), String.class, projectId, owner.workspaceId(),
                owner.id(), attachmentId).isEmpty()) {
                throw notFound();
            }
            erasure.deleteAttachment(owner, projectId, attachmentId);
        });
    }

    /**
     * Issues a single-use grant for an approved attachment in the current device session.
     *
     * @param principal current owner and session
     * @param projectId owned project
     * @param attachmentId approved upload
     * @param kind requested media type
     * @param ocrReviewed explicit acknowledgement when saving scanned text
     * @return token and one-minute expiry
     */
    DownloadGrant grant(Principal principal, String projectId, String attachmentId, Kind kind,
        boolean ocrReviewed) {
        requirePdfEnabled(kind);
        Kind actual = actualKind(principal.user(), projectId, attachmentId, kind);
        return issueGrant(principal, actual.grantScope(attachmentId), null, () -> {
            Stored row = approved(principal.user(), projectId, attachmentId, actual);
            if (row.pdfOcr() && !ocrReviewed) {
                throw new AssistantApiException(HttpStatus.CONFLICT, "OCR_REVIEW_REQUIRED",
                    "Acknowledge that scanned text can contain errors before saving it.");
            }
            return row;
        });
    }

    /**
     * Confirms the owner can use this currently approved source in a private artifact version.
     *
     * @param owner authenticated owner
     * @param projectId owning project
     * @param attachmentId approved upload
     */
    public void requireApprovedSource(User owner, String projectId, String attachmentId) {
        requireReady();
        approved(owner, projectId, attachmentId, Kind.PNG);
    }

    /**
     * Issues one protected grant for an already authorized private artifact version.
     *
     * @param principal current owner and session
     * @param projectId owned project
     * @param attachmentId approved upload
     * @param versionId authorized artifact version
     * @return token scoped only to this version
     */
    public DownloadGrant grantArtifact(Principal principal, String projectId, String attachmentId,
        String versionId) {
        return issueGrant(principal, artifactScope(versionId), null,
            () -> approved(principal.user(), projectId, attachmentId, Kind.PNG));
    }

    /**
     * Issues a lease for an independently authorized project publication.
     *
     * @param principal actual recipient and device session
     * @param source previously checked exact project publication and role generation
     * @return one-use lease for that publication and recipient
     */
    public DownloadGrant grantPublication(Principal principal, PublishedSource source) {
        return issueGrant(principal, publicationScope(source.publicationId()), source.generation(),
            () -> approvedPublication(principal, source));
    }

    private DownloadGrant issueGrant(Principal principal, String scope, String generation,
        Supplier<Stored> approval) {
        requireReady();
        return transactions.execute(status -> {
            accounts.lockActive(principal.user().id());
            var attachment = approval.get();
            byte[] entropy = new byte[32];
            RANDOM.nextBytes(entropy);
            String token = Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
            long expires = System.currentTimeMillis() + GRANT_MILLIS;
            jdbc.update(
                """
                    DELETE FROM assistant_attachment_grant
                    WHERE attachment_id = ? AND session_id = ? AND grant_scope = ?
                    """,
                attachment.id(), principal.sessionId(), scope);
            jdbc.update(
                """
                    INSERT INTO assistant_attachment_grant (token_digest, attachment_id, session_id, expires_at, grant_scope)
                    VALUES (?, ?, ?, ?, ?)
                    """,
                grantDigest(token, scope, generation), attachment.id(),
                principal.sessionId(), expires, scope);
            return new DownloadGrant(token, Instant.ofEpochMilli(expires));
        });
    }

    /**
     * Redeems a current grant and loads the verified, sanitized PNG.
     *
     * @param principal current owner and session
     * @param projectId owned project
     * @param attachmentId approved upload
     * @param token short-lived grant sent in a private header
     * @param kind requested media type
     * @return bounded approved bytes
     */
    byte[] download(Principal principal, String projectId, String attachmentId,
        String token, Kind kind) {
        requirePdfEnabled(kind);
        Kind actual = actualKind(principal.user(), projectId, attachmentId, kind);
        return redeemGrant(principal, token, actual.grantScope(attachmentId), null,
            () -> approved(principal.user(), projectId, attachmentId, actual));
    }

    /**
     * Reads only the canonical text of an approved private PDF.
     *
     * @param owner authenticated workspace owner
     * @param projectId owned project
     * @param attachmentId private PDF attachment
     * @return verified plain text
     */
    PdfText pdfText(User owner, String projectId, String attachmentId) {
        requireUploadReady(Kind.PDF);
        Supplier<Stored> approval = () -> approved(owner, projectId, attachmentId, Kind.PDF);
        Stored row = approval.get();
        String text = new String(readApproved(row, approval), StandardCharsets.UTF_8);
        return new PdfText(text, row.pdfOcr());
    }

    /**
     * Consumes a private artifact version grant and loads its verified approved PNG.
     *
     * @param principal current owner session
     * @param projectId owned project
     * @param attachmentId approved upload referenced by the version
     * @param versionId authorized version
     * @param token one-use version grant
     * @return exact approved PNG bytes
     */
    public byte[] downloadArtifact(Principal principal, String projectId, String attachmentId,
        String versionId, String token) {
        return redeemGrant(principal, token, artifactScope(versionId), null,
            () -> approved(principal.user(), projectId, attachmentId, Kind.PNG));
    }

    /**
     * Consumes a lease for an independently authorized project publication.
     *
     * @param principal actual recipient and device session
     * @param source previously checked exact project publication and role generation
     * @param token one-use publication lease
     * @return exact approved source bytes
     */
    public byte[] downloadPublication(Principal principal, PublishedSource source, String token) {
        return redeemGrant(principal, token, publicationScope(source.publicationId()),
            source.generation(), () -> approvedPublication(principal, source));
    }

    private byte[] redeemGrant(Principal principal, String token, String scope, String generation,
        Supplier<Stored> approval) {
        requireReady();
        Stored row = approval.get();
        if (token == null || !DOWNLOAD_TOKEN.matcher(token).matches()) {
            throw invalidGrant();
        }
        int consumed = jdbc.update("""
            DELETE FROM assistant_attachment_grant
            WHERE token_digest = ? AND attachment_id = ? AND session_id = ?
                AND expires_at > ? AND grant_scope = ?
            """, grantDigest(token, scope, generation), row.id(), principal.sessionId(),
            System.currentTimeMillis(), scope);
        if (consumed != 1) {
            throw invalidGrant();
        }
        return readApproved(row, approval);
    }

    private byte[] readApproved(Stored row, Supplier<Stored> approval) {
        try {
            byte[] bytes = objects.read(row.key(true), row.kind().maxApprovedBytes());
            if (bytes.length != row.size() || !digest(bytes).equals(row.approvedDigest())) {
                throw unavailable();
            }
            approval.get();
            return bytes;
        } catch (IOException | SdkException failure) {
            if (blocked(row)) {
                throw notFound();
            }
            throw unavailable();
        }
    }

    /**
     * Processes bounded queued work and retries private object deletion after recoverable errors.
     */
    @Scheduled(fixedDelay = 750)
    public void work() {
        if (objects == null) {
            return;
        }
        try {
            cleanup();
            cleanupRaw();
            if (!available() || journal.dispatchPaused()) {
                return;
            }
            long now = System.currentTimeMillis();
            jdbc.update(
                """
                    UPDATE assistant_attachment
                    SET status = CASE media_kind WHEN 'WAVE' THEN 'wave_rejected'
                        WHEN 'PDF' THEN 'pdf_rejected' WHEN 'EDIT' THEN 'edit_rejected' ELSE 'rejected' END,
                        error_code = 'UPLOAD_INCOMPLETE',
                        due_at = ?, updated_at = ?
                    WHERE status IN ('receiving','wave_receiving','pdf_receiving','edit_receiving') AND due_at <= ?
                    """,
                now, now, now);
            for (Stored row : jdbc.query(
                SELECT
                    + """
                        WHERE (a.status IN ('quarantined','edit_quarantined') OR (a.status = 'wave_quarantined' AND ? = TRUE)
                            OR (a.status = 'pdf_quarantined' AND ? = TRUE))
                            AND a.due_at <= ? AND EXISTS (
                            SELECT 1 FROM assistant_workspace_member wm
                            WHERE wm.account_id = a.account_id AND wm.workspace_id = a.workspace_id
                        )
                        AND (image_edit.output_attachment_id IS NULL
                            OR (image_edit_source.id IS NOT NULL AND image_edit_member.account_id IS NOT NULL))
                        ORDER BY a.due_at, a.id LIMIT 2
                        """,
                ATTACHMENT, audioEnabled, pdfEnabled, now)) {
                scan(row);
            }
        } catch (SdkException | DataAccessException | TransactionException failure) {
            LOG.warn("An attachment worker operation could not be completed.");
        }
    }

    private void scan(Stored pending) {
        if (blocked(pending)) {
            return;
        }
        Scan result = assess(pending);
        try {
            transactions.executeWithoutResult(status -> {
                accounts.lockActive(pending.accountId());
                var current = jdbc.query(SELECT + """
                    WHERE a.id = ? AND a.status = ? AND EXISTS (
                        SELECT 1 FROM assistant_workspace_member wm
                        WHERE wm.account_id = a.account_id AND wm.workspace_id = a.workspace_id
                    )
                    """, ATTACHMENT, pending.id(), pending.status()).stream().findFirst();
                current.filter(row -> !blocked(row)).ifPresent(row -> publish(row, result));
            });
        } catch (AssistantApiException failure) {
            if (!blocked(pending)) {
                LOG.warn("Attachment processing stopped because its scope is unavailable.");
            }
        } catch (SdkException failure) {
            retry(pending, "STORAGE_UNAVAILABLE");
        }
    }

    private Scan assess(Stored pending) {
        if (pending.editProof() != null && !localImageEditsEnabled) {
            return Scan.failed("LOCAL_IMAGE_EDIT_DISABLED", false);
        }
        try {
            byte[] bytes = objects.read(pending.key(false), pending.kind().maxUploadBytes());
            if (!digest(bytes).equals(pending.originalDigest())) {
                return Scan.failed("UPLOAD_INTEGRITY", false);
            }
            return switch (scanner.scan(bytes)) {
                case CLEAN -> canonicalize(pending.kind(), bytes);
                case REJECTED -> Scan.failed("MALWARE_DETECTED", false);
                case UNAVAILABLE -> Scan.failed("SCANNER_UNAVAILABLE", true);
            };
        } catch (AttachmentPdfExtractor.Unavailable failure) {
            return Scan.failed("PDF_EXTRACTOR_UNAVAILABLE", true);
        } catch (IllegalArgumentException failure) {
            return Scan.failed(pending.kind().invalidMediaCode(), false);
        } catch (IOException | SdkException failure) {
            return Scan.failed("STORAGE_UNAVAILABLE", true);
        }
    }

    private Scan canonicalize(Kind kind, byte[] bytes) {
        try {
            return new Scan(kind.canonicalize(bytes), false, null, false);
        } catch (IllegalArgumentException unsupported) {
            if (kind == Kind.PDF && pdfExtractor != null) {
                var result = pdfExtractor.extract(bytes, pdfOcrEnabled);
                return new Scan(result.bytes(), result.approximate(), null, false);
            }
            throw unsupported;
        }
    }

    private void publish(Stored row, Scan result) {
        if (result.bytes() != null) {
            objects.put(row.key(true), result.bytes());
            long now = System.currentTimeMillis();
            jdbc.update(
                """
                    UPDATE assistant_attachment SET status = ?, approved_digest = ?, size_bytes = ?,
                        pdf_ocr = ?, error_code = NULL, due_at = ?, updated_at = ? WHERE id = ? AND status = ?
                    """,
                row.kind().status(APPROVED), digest(result.bytes()), result.bytes().length,
                result.pdfOcr(), now, now, row.id(), row.status());
        } else if (result.retriable()) {
            retry(row, result.failure());
        } else {
            reject(row, result.failure());
        }
    }

    private void retry(Stored row, String code) {
        if (row.attempts() >= 2) {
            reject(row, code);
            return;
        }
        long now = System.currentTimeMillis();
        jdbc.update("""
            UPDATE assistant_attachment SET scan_attempts = scan_attempts + 1,
                error_code = ?, due_at = ?, updated_at = ? WHERE id = ? AND status = ?
                AND scan_attempts = ?
            """, code, now + 2_000, now, row.id(), row.status(), row.attempts());
    }

    private void reject(Stored row, String code) {
        long now = System.currentTimeMillis();
        jdbc.update(
            """
                UPDATE assistant_attachment SET status = ?, error_code = ?, due_at = ?, updated_at = ?
                WHERE id = ? AND status = ? AND scan_attempts = ?
                """,
            row.kind().status("rejected"), code, now, now, row.id(), row.status(), row.attempts());
    }

    private void cleanup() {
        long now = System.currentTimeMillis();
        jdbc.update("DELETE FROM assistant_attachment_grant WHERE expires_at <= ?", now);
        for (Cleanup work : jdbc.query(
            """
                SELECT event_id, account_id, workspace_id, attachment_id FROM assistant_attachment_cleanup
                WHERE due_at <= ? ORDER BY due_at, event_id LIMIT 2
                """,
            CLEANUP, now)) {
            try {
                if (work.attachmentId() == null) {
                    if (!objects.deletePage(AttachmentObjects.prefix(work.accountId()))) {
                        continue;
                    }
                } else {
                    objects.delete(AttachmentObjects.key(work.accountId(), work.workspaceId(),
                        work.attachmentId(), false));
                    objects.delete(AttachmentObjects.key(work.accountId(), work.workspaceId(),
                        work.attachmentId(), true));
                }
                jdbc.update("DELETE FROM assistant_attachment_cleanup WHERE event_id = ?",
                    work.id());
            } catch (SdkException failure) {
                jdbc.update("UPDATE assistant_attachment_cleanup SET due_at = ? WHERE event_id = ?",
                    now + 15_000, work.id());
            }
        }
    }

    private void cleanupRaw() {
        for (Stored row : jdbc.query(
            SELECT
                + """
                    WHERE a.raw_cleaned = FALSE AND a.status IN
                        ('approved','rejected','wave_approved','wave_rejected','pdf_approved','pdf_rejected',
                            'edit_approved','edit_rejected')
                        AND a.due_at <= ?
                    ORDER BY a.due_at, a.id LIMIT 2
                    """,
            ATTACHMENT, System.currentTimeMillis())) {
            try {
                objects.delete(row.key(false));
                if (row.is("rejected")) {
                    objects.delete(row.key(true));
                }
                jdbc.update("UPDATE assistant_attachment SET raw_cleaned = TRUE WHERE id = ?",
                    row.id());
            } catch (SdkException failure) {
                jdbc.update("UPDATE assistant_attachment SET due_at = ? WHERE id = ?",
                    System.currentTimeMillis() + 15_000, row.id());
            }
        }
    }

    private Kind actualKind(User owner, String projectId, String attachmentId, Kind requested) {
        requireReady();
        return requested == Kind.PNG ? approved(owner, projectId, attachmentId, Kind.PNG).kind()
            : requested;
    }

    private Stored approved(User owner, String projectId, String attachmentId, Kind kind) {
        Stored row = scoped(owner, projectId, attachmentId, kind);
        if (!row.is(APPROVED)) {
            throw new AssistantApiException(HttpStatus.CONFLICT, "CONFLICT",
                "The attachment has not passed validation.");
        }
        return row;
    }

    private Stored approvedPublication(Principal principal, PublishedSource source) {
        requireId(source.attachmentId());
        return jdbc
            .query(
                SELECT + OWNED
                    + " AND a.id = ? AND a.status IN ('approved','edit_approved') AND a.approved_digest IS NOT NULL",
                ATTACHMENT, source.projectId(), principal.user().workspaceId(), source.ownerId(),
                source.attachmentId())
            .stream().filter(row -> !blocked(row)).findFirst()
            .orElseThrow(AssistantAttachments::notFound);
    }

    private Stored scoped(User owner, String projectId, String attachmentId, Kind kind) {
        requireId(attachmentId);
        projects.requireOwned(owner, projectId);
        return jdbc.query(SELECT + OWNED + " AND a.id = ?" + kind.routeScope(), ATTACHMENT,
            projectId, owner.workspaceId(), owner.id(), attachmentId).stream()
            .filter(row -> !blocked(row)).findFirst().orElseThrow(AssistantAttachments::notFound);
    }

    private long count(String condition, Object... parameters) {
        Long value = jdbc.queryForObject("SELECT COUNT(*) FROM assistant_attachment a " + condition,
            Long.class, parameters);
        return value == null ? 0 : value;
    }

    private boolean blocked(Stored row) {
        if (journal.blocksAttachment(row.accountId(), row.workspaceId(), row.id())) {
            return true;
        }
        Proof proof = row.editProof();
        return row.kind() == Kind.EDIT
            ? proof == null || !proof.allows(journal, row.accountId(), row.workspaceId())
            : proof != null;
    }

    void requireReady() {
        if (!enabled || objects == null || scanner == null || !journal.enabled()) {
            throw new AssistantApiException(HttpStatus.FORBIDDEN, "CAPABILITY_UNAVAILABLE",
                "Private binary attachments are unavailable.");
        }
        if (!journal.acceptsErasure()) {
            throw unavailable();
        }
    }

    void requireUploadReady(Kind kind) {
        requireReady();
        requirePdfEnabled(kind);
        if (kind == Kind.WAVE && !audioEnabled) {
            throw new AssistantApiException(HttpStatus.FORBIDDEN, "CAPABILITY_UNAVAILABLE",
                "New audio uploads are unavailable.");
        }
    }

    private void requirePdfEnabled(Kind kind) {
        if (kind == Kind.PDF && !pdfEnabled) {
            throw new AssistantApiException(HttpStatus.FORBIDDEN, "CAPABILITY_UNAVAILABLE",
                "Local PDF text uploads and previews are unavailable.");
        }
    }

    private static void requireId(String id) {
        try {
            if (id != null && id.length() == 36 && UUID.fromString(id).toString().equals(id)) {
                return;
            }
        } catch (IllegalArgumentException failure) {
            throw invalidId();
        }
        throw invalidId();
    }

    private static String artifactScope(String versionId) {
        requireId(versionId);
        return "artifact:" + versionId;
    }

    private static String publicationScope(String publicationId) {
        requireId(publicationId);
        return "publication:" + publicationId;
    }

    static String grantDigest(String token, String scope, String generation) {
        // Older servers only look up the unscoped token digest on the original route.
        String binding = generation == null ? "" : generation + "\n";
        String input = ORIGINAL_SCOPE.equals(scope) ? token
            : "assistant-attachment-grant/v1\n" + scope + "\n" + binding + token;
        return digest(input.getBytes(StandardCharsets.US_ASCII));
    }

    private static String digest(byte[] content) {
        return HexFormat.of().formatHex(sha256(content));
    }

    private static String uploadId(User owner, String projectId, String key, Kind kind) {
        // Recover the same identity even from a database snapshot taken before upload.
        String domain = switch (kind) {
            case PNG -> "assistant-attachment-upload/v1";
            case EDIT -> "assistant-local-image-edit/v1";
            case WAVE -> "assistant-audio-upload/v1";
            case PDF -> "assistant-pdf-upload/v1";
        };
        String scope = domain + "\n" + owner.id() + "\n"
            + owner.workspaceId() + "\n" + projectId + "\n" + key;
        byte[] value = sha256(scope.getBytes(StandardCharsets.US_ASCII));
        value[6] = (byte) ((value[6] & 0x0f) | 0x80);
        value[8] = (byte) ((value[8] & 0x3f) | 0x80);
        ByteBuffer identifier = ByteBuffer.wrap(value);
        return new UUID(identifier.getLong(), identifier.getLong()).toString();
    }

    private static byte[] sha256(byte[] content) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(content);
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 checksum unavailable.", failure);
        }
    }

    private static AssistantApiException invalidId() {
        return new AssistantApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
            "A valid attachment identifier or idempotency key is required.");
    }

    private static AssistantApiException invalidGrant() {
        return new AssistantApiException(HttpStatus.FORBIDDEN, "ATTACHMENT_GRANT_INVALID",
            "Request a new attachment download authorization.");
    }

    private static AssistantApiException notFound() {
        return new AssistantApiException(HttpStatus.NOT_FOUND, "NOT_FOUND",
            "Attachment not found.");
    }

    private static AssistantApiException unavailable() {
        return new AssistantApiException(HttpStatus.SERVICE_UNAVAILABLE, "CAPABILITY_UNAVAILABLE",
            "The attachment service is temporarily unavailable.");
    }

    @PreDestroy
    void close() {
        if (objects != null) {
            objects.close();
        }
    }

    /**
     * Visible attachment processing metadata.
     *
     * @param id attachment identifier
     * @param state quarantined, approved or rejected
     * @param size stored content length
     * @param createdAt upload creation time
     * @param width approved width, if available
     * @param height approved height, if available
     * @param audio approved audio format, if available
     * @param errorCode stable error code for pending or rejected work
     * @param localEdit original attachment and approved operation when created by a local edit
     * @param approximateText indicates the approved PDF text came from OCR
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Attachment(String id, String state, int size, Instant createdAt, Integer width,
        Integer height, AudioInfo audio, String errorCode, Origin localEdit,
        Boolean approximateText) {
    }

    /**
     * Metadata derived from a validated PCM WAVE header.
     *
     * @param sampleRate samples per second
     * @param channels one for mono or two for stereo
     * @param durationMillis sample duration rounded up to milliseconds
     */
    public record AudioInfo(int sampleRate, int channels, int durationMillis) {
    }

    /**
     * Single-use, short-lived asset download authorization.
     *
     * @param token opaque token sent in the download header
     * @param expiresAt expiry instant
     */
    public record DownloadGrant(String token, Instant expiresAt) {

        @Override
        public String toString() {
            return "DownloadGrant[protected]";
        }
    }

    /**
     * Internal binding produced from an authorized collaboration publication.
     *
     * @param ownerId current source project owner
     * @param projectId owning project
     * @param attachmentId approved PNG behind the immutable publication
     * @param publicationId exact published version reference
     * @param roleGrantId current accepted member grant, or null for the owner
     */
    public record PublishedSource(String ownerId, String projectId, String attachmentId,
        String publicationId, String roleGrantId) {

        private String generation() {
            return roleGrantId == null ? "owner" : roleGrantId;
        }

        @Override
        public String toString() {
            return "PublishedSource[protected]";
        }
    }

    record PdfText(String text, boolean approximate) {

        @Override
        public String toString() {
            return "PdfText[protected]";
        }
    }

    private record Scan(byte[] bytes, boolean pdfOcr, String failure, boolean retriable) {

        private static Scan failed(String failure, boolean retriable) {
            return new Scan(null, false, failure, retriable);
        }
    }

    private record Cleanup(String id, String accountId, String workspaceId, String attachmentId) {
    }

    enum Kind {

        PNG("", "image/png", "image.png"),
        WAVE("wave_", "audio/wav", "audio.wav"),
        PDF("pdf_", "application/pdf", "extracted.txt"),
        EDIT("edit_", "image/png", "image.png");

        private final String prefix;
        private final String mime;
        private final String filename;

        Kind(String prefix, String mime, String filename) {
            this.prefix = prefix;
            this.mime = mime;
            this.filename = filename;
        }

        String mime() {
            return mime;
        }

        boolean image() {
            return this == PNG || this == EDIT;
        }

        String routeScope() {
            return this == PNG ? " AND a.media_kind IN ('PNG','EDIT')"
                : " AND a.media_kind = '" + name() + "'";
        }

        String approvedMime() {
            return this == PDF ? "text/plain; charset=UTF-8" : mime;
        }

        String filename() {
            return filename;
        }

        int maxUploadBytes() {
            return this == PDF ? AttachmentPdf.MAX_BYTES : AttachmentPng.MAX_BYTES;
        }

        int maxApprovedBytes() {
            return this == PDF ? AttachmentPdf.MAX_TEXT_BYTES : AttachmentPng.MAX_BYTES;
        }

        String invalidInputMessage() {
            return switch (this) {
                case PNG, EDIT ->
                    "Upload a non-interlaced RGB or RGBA PNG within the file and dimension limits.";
                case WAVE -> "Upload a 16-bit mono or stereo PCM WAVE within 1 MiB and 30 seconds.";
                case PDF -> "Upload a basic one-page PDF 1.4 text file within 32 KiB.";
            };
        }

        String invalidMediaCode() {
            return switch (this) {
                case PNG, EDIT -> "INVALID_IMAGE";
                case WAVE -> "INVALID_AUDIO";
                case PDF -> "INVALID_PDF";
            };
        }

        String status(String logical) {
            return prefix + logical;
        }

        String grantScope(String attachmentId) {
            requireId(attachmentId);
            return switch (this) {
                case PNG -> ORIGINAL_SCOPE;
                case EDIT -> "image-edit:" + attachmentId;
                case WAVE -> "audio:" + attachmentId;
                case PDF -> "pdf-text:" + attachmentId;
            };
        }

        MediaInfo inspect(byte[] bytes, boolean extendedPdf) {
            if (image()) {
                var image = AttachmentPng.inspect(bytes);
                return new MediaInfo(image.width(), image.height(), null, null, null);
            }
            if (this == WAVE) {
                var wave = AttachmentWave.inspect(bytes);
                return new MediaInfo(0, 0, wave.sampleRate(), wave.channels(),
                    wave.durationMillis());
            }
            if (extendedPdf) {
                AttachmentPdf.requireGeneralHeader(bytes);
            } else {
                AttachmentPdf.requireHeader(bytes);
            }
            return new MediaInfo(0, 0, null, null, null);
        }

        byte[] canonicalize(byte[] bytes) {
            return switch (this) {
                case PNG -> AttachmentPng.sanitize(bytes).bytes();
                case EDIT -> AttachmentPngTransform.canonicalizeRendered(bytes);
                case WAVE -> AttachmentWave.canonicalize(bytes);
                case PDF -> AttachmentPdf.extract(bytes);
            };
        }
    }

    private record ImageEditRequest(String sessionId, Origin origin, String membershipRevision,
        String sourceDigest) {

        private boolean matches(Proof other) {
            return other != null && origin.equals(other.origin())
                && membershipRevision.equals(other.membershipRevision())
                && sourceDigest.equals(other.sourceDigest());
        }
    }

    private record MediaInfo(int width, int height, Integer sampleRate, Integer channels,
        Integer durationMillis) {
    }

    private record Stored(String id, String accountId, String workspaceId, String projectId,
        String status, int size, int width, int height, String originalDigest,
        String approvedDigest,
        String failure, int attempts, long createdAt, Kind kind, int sampleRate, int channels,
        int durationMillis, Proof editProof, boolean pdfOcr) {

        private boolean is(String logical) {
            return status.equals(kind.status(logical));
        }

        private Attachment snapshot() {
            boolean approvedImage = is(APPROVED) && kind.image();
            AudioInfo audio = is(APPROVED) && kind == Kind.WAVE
                ? new AudioInfo(sampleRate, channels, durationMillis) : null;
            return new Attachment(id,
                is(RECEIVING) ? QUARANTINED : status.substring(kind.prefix.length()),
                size, Instant.ofEpochMilli(createdAt), approvedImage ? width : null,
                approvedImage ? height : null, audio, failure,
                editProof == null ? null : editProof.origin(), pdfOcr ? Boolean.TRUE : null);
        }

        private String key(boolean approved) {
            return AttachmentObjects.key(accountId, workspaceId, id, approved);
        }
    }
}

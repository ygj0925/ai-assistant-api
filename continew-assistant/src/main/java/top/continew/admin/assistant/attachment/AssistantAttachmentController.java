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

import cn.dev33.satoken.annotation.SaIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.feiniaojin.gracefulresponse.api.ExcludeFromGracefulResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import top.continew.admin.assistant.account.AssistantAccountController.ApiError;
import top.continew.admin.assistant.account.AssistantAccounts;
import top.continew.admin.assistant.account.AssistantApiException;
import top.continew.admin.assistant.attachment.AssistantAttachments.Attachment;
import top.continew.admin.assistant.attachment.AssistantAttachments.DownloadGrant;
import top.continew.admin.assistant.attachment.AssistantAttachments.Kind;
import top.continew.starter.extension.tenant.annotation.TenantIgnore;
import top.continew.starter.log.annotation.Log;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.List;

/**
 * Authenticated private attachment intake and short-lived download.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
@RestController
@RequestMapping("/api/assistant/v1/projects/{projectId}/{attachmentKind:attachments|audio-attachments|pdf-attachments}")
@ConditionalOnProperty(name = "assistant.enabled", havingValue = "true")
@ExcludeFromGracefulResponse
@SaIgnore
@TenantIgnore
@Log(ignore = true)
public class AssistantAttachmentController {

    private static final String WORKSPACE = "X-Workspace-Id";
    private static final int MAX_EDIT_REQUEST_BYTES = 512;
    private final AssistantAccounts accounts;
    private final AssistantAttachments attachments;
    private final ObjectReader editReader;

    /**
     * Connects attachment operations to consumer credentials and workspace membership.
     *
     * @param accounts current account authentication
     * @param attachments private attachment lifecycle
     * @param mapper bounded image-edit JSON reader configuration
     */
    public AssistantAttachmentController(AssistantAccounts accounts,
        AssistantAttachments attachments, ObjectMapper mapper) {
        this.accounts = accounts;
        this.attachments = attachments;
        this.editReader = mapper.reader().with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    /**
     * Quarantines one raw upload of the selected media kind.
     *
     * @param authorization consumer bearer header
     * @param workspace selected workspace
     * @param projectId target project
     * @param attachmentKind media-specific route
     * @param idempotencyKey client-issued retry identifier
     * @param request bounded binary body
     * @return queued attachment status
     */
    @PostMapping
    public ResponseEntity<Attachment> upload(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace,
        @PathVariable("projectId") String projectId,
        @PathVariable("attachmentKind") String attachmentKind,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
        HttpServletRequest request) {
        var owner = accounts.authenticate(authorization, workspace).user();
        Kind kind = mediaKind(attachmentKind);
        attachments.requireUploadReady(kind);
        if (!kind.mime().equalsIgnoreCase(request.getContentType())) {
            throw new AssistantApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "VALIDATION_ERROR",
                "Use the media type required for this upload route.");
        }
        byte[] content = readUpload(request, kind);
        return json(HttpStatus.ACCEPTED,
            attachments.upload(owner, projectId, idempotencyKey, content, kind));
    }

    /**
     * Quarantines a confirmed crop and quarter-turn of an owned original PNG.
     *
     * @param authorization consumer bearer header
     * @param workspace selected workspace
     * @param projectId owning project
     * @param attachmentKind media-specific route
     * @param attachmentId exact original PNG
     * @param idempotencyKey client retry identifier
     * @param request reviewed image operation
     * @return current quarantined or completed edit
     */
    @PostMapping(path = "/{attachmentId}/local-image-edits",
        consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Attachment> localImageEdit(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace,
        @PathVariable("projectId") String projectId,
        @PathVariable("attachmentKind") String attachmentKind,
        @PathVariable("attachmentId") String attachmentId,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
        HttpServletRequest request) {
        var principal = accounts.authenticate(authorization, workspace);
        if (mediaKind(attachmentKind) != Kind.PNG) {
            throw new AssistantApiException(HttpStatus.NOT_FOUND, "NOT_FOUND",
                "Attachment not found.");
        }
        var result = attachments.localImageEdit(principal, projectId, attachmentId, idempotencyKey,
            reviewedEdit(readEdit(request)));
        accounts.authenticate(authorization, workspace);
        return json(HttpStatus.ACCEPTED, result);
    }

    private JsonNode readEdit(HttpServletRequest request) {
        if (request.getContentLengthLong() > MAX_EDIT_REQUEST_BYTES) {
            throw oversizedEdit();
        }
        try {
            byte[] bytes = request.getInputStream().readNBytes(MAX_EDIT_REQUEST_BYTES + 1);
            if (bytes.length > MAX_EDIT_REQUEST_BYTES) {
                throw oversizedEdit();
            }
            return editReader.readTree(bytes);
        } catch (IOException failure) {
            throw invalidEdit();
        }
    }

    private static AssistantApiException oversizedEdit() {
        return new AssistantApiException(HttpStatus.PAYLOAD_TOO_LARGE, "VALIDATION_ERROR",
            "The image edit request exceeds its maximum size.");
    }

    private static AttachmentPngTransform.Edit reviewedEdit(JsonNode request) {
        if (request == null || !request.isObject() || request.size() != 6
            || !request.path("confirm").isBoolean() || !request.path("confirm").booleanValue()) {
            throw invalidEdit();
        }
        try {
            return new AttachmentPngTransform.Edit(coordinate(request, "x"),
                coordinate(request, "y"),
                coordinate(request, "width"), coordinate(request, "height"),
                coordinate(request, "rotation"));
        } catch (IllegalArgumentException failure) {
            throw invalidEdit();
        }
    }

    private static int coordinate(JsonNode request, String field) {
        JsonNode value = request.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw invalidEdit();
        }
        return value.intValue();
    }

    private static AssistantApiException invalidEdit() {
        return new AssistantApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
            "Review and confirm an integer crop and clockwise quarter-turn of the original PNG.");
    }

    /**
     * Lists attachment metadata for an owned project.
     *
     * @param authorization consumer bearer header
     * @param workspace selected workspace
     * @param projectId target project
     * @param attachmentKind media-specific route
     * @return visible status records
     */
    @GetMapping
    public ResponseEntity<Items> list(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace,
        @PathVariable("projectId") String projectId,
        @PathVariable("attachmentKind") String attachmentKind) {
        var owner = accounts.authenticate(authorization, workspace).user();
        return json(HttpStatus.OK,
            new Items(attachments.list(owner, projectId, mediaKind(attachmentKind))));
    }

    /**
     * Reads the current processing state.
     *
     * @param authorization consumer bearer header
     * @param workspace selected workspace
     * @param projectId target project
     * @param attachmentKind media-specific route
     * @param attachmentId scoped upload
     * @return visible attachment state
     */
    @GetMapping("/{attachmentId}")
    public ResponseEntity<Attachment> get(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace,
        @PathVariable("projectId") String projectId,
        @PathVariable("attachmentKind") String attachmentKind,
        @PathVariable("attachmentId") String attachmentId) {
        var owner = accounts.authenticate(authorization, workspace).user();
        return json(HttpStatus.OK,
            attachments.get(owner, projectId, attachmentId, mediaKind(attachmentKind)));
    }

    /**
     * Reads the verified plain text of an approved PDF attachment.
     *
     * @param authorization consumer bearer header
     * @param workspace selected workspace
     * @param projectId target project
     * @param attachmentKind media-specific route
     * @param attachmentId approved PDF attachment
     * @return the digest-verified plain text
     */
    @GetMapping("/{attachmentId}/text")
    public ResponseEntity<TextPreview> text(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace,
        @PathVariable("projectId") String projectId,
        @PathVariable("attachmentKind") String attachmentKind,
        @PathVariable("attachmentId") String attachmentId) {
        var owner = accounts.authenticate(authorization, workspace).user();
        if (mediaKind(attachmentKind) != Kind.PDF) {
            throw new AssistantApiException(HttpStatus.NOT_FOUND, "NOT_FOUND",
                "Attachment not found.");
        }
        var text = attachments.pdfText(owner, projectId, attachmentId);
        accounts.authenticate(authorization, workspace);
        return json(HttpStatus.OK,
            new TextPreview(attachmentId, text.text(), text.approximate() ? Boolean.TRUE : null));
    }

    /**
     * Cancels or revokes an attachment immediately and queues object cleanup.
     *
     * @param authorization consumer bearer header
     * @param workspace selected workspace
     * @param projectId target project
     * @param attachmentKind media-specific route
     * @param attachmentId attachment to revoke
     * @return empty response
     */
    @DeleteMapping("/{attachmentId}")
    public ResponseEntity<Void> delete(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace,
        @PathVariable("projectId") String projectId,
        @PathVariable("attachmentKind") String attachmentKind,
        @PathVariable("attachmentId") String attachmentId) {
        var owner = accounts.authenticate(authorization, workspace).user();
        attachments.delete(owner, projectId, attachmentId, mediaKind(attachmentKind));
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    /**
     * Issues an authorization for one approved download.
     *
     * @param authorization consumer bearer header
     * @param workspace selected workspace
     * @param projectId target project
     * @param attachmentKind media-specific route
     * @param attachmentId approved attachment
     * @param ocrReviewed acknowledgement for an approximate PDF transcript
     * @return opaque authorization and expiry
     */
    @PostMapping("/{attachmentId}/download-grant")
    public ResponseEntity<DownloadGrant> grant(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace,
        @PathVariable("projectId") String projectId,
        @PathVariable("attachmentKind") String attachmentKind,
        @PathVariable("attachmentId") String attachmentId,
        @RequestHeader(value = "X-Assistant-OCR-Reviewed",
            required = false) List<String> ocrReviewed) {
        var principal = accounts.authenticate(authorization, workspace);
        return json(HttpStatus.CREATED,
            attachments.grant(principal, projectId, attachmentId, mediaKind(attachmentKind),
                List.of("true").equals(ocrReviewed)));
    }

    /**
     * Redeems and invalidates the short-lived authorization.
     *
     * @param authorization consumer bearer header
     * @param workspace selected workspace
     * @param projectId target project
     * @param attachmentKind media-specific route
     * @param attachmentId approved attachment
     * @param grant one-time, session-scoped download authorization
     * @return approved attachment
     */
    @GetMapping("/{attachmentId}/download")
    public ResponseEntity<byte[]> download(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace,
        @PathVariable("projectId") String projectId,
        @PathVariable("attachmentKind") String attachmentKind,
        @PathVariable("attachmentId") String attachmentId,
        @RequestHeader(value = "X-Attachment-Grant", required = false) String grant) {
        var principal = accounts.authenticate(authorization, workspace);
        Kind kind = mediaKind(attachmentKind);
        byte[] content = attachments.download(principal, projectId, attachmentId, grant, kind);
        accounts.authenticate(authorization, workspace);
        String disposition =
            ContentDisposition.attachment().filename(kind.filename()).build().toString();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .contentLength(content.length)
            .contentType(MediaType.parseMediaType(kind.approvedMime()))
            .header(HttpHeaders.CONTENT_DISPOSITION, disposition)
            .header("X-Content-Type-Options", "nosniff").body(content);
    }

    /**
     * Returns a sanitized local attachment error.
     *
     * @param failure expected API failure
     * @return documented consumer failure
     */
    @ExceptionHandler(AssistantApiException.class)
    public ResponseEntity<ApiError> handle(AssistantApiException failure) {
        return json(failure.status(), new ApiError(failure.code(), failure.getMessage()));
    }

    /**
     * Conceals object and database details on storage failures.
     *
     * @return temporary capability error
     */
    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    public ResponseEntity<ApiError> storageFailure() {
        return json(HttpStatus.SERVICE_UNAVAILABLE,
            new ApiError("CAPABILITY_UNAVAILABLE",
                "The attachment service is temporarily unavailable."));
    }

    private static byte[] readUpload(HttpServletRequest request, Kind kind) {
        int limit = kind.maxUploadBytes();
        if (request.getContentLengthLong() > limit) {
            throw oversized(kind);
        }
        try {
            byte[] bytes = request.getInputStream().readNBytes(limit + 1);
            if (bytes.length > limit) {
                throw oversized(kind);
            }
            return bytes;
        } catch (IOException failure) {
            throw new AssistantApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                "The upload could not be read in full.");
        }
    }

    private static AssistantApiException oversized(Kind kind) {
        return new AssistantApiException(HttpStatus.PAYLOAD_TOO_LARGE, "VALIDATION_ERROR",
            kind == Kind.PDF ? "The PDF upload exceeds 32 KiB."
                : "The attachment upload exceeds 1 MiB.");
    }

    private static Kind mediaKind(String route) {
        return switch (route) {
            case "audio-attachments" -> Kind.WAVE;
            case "pdf-attachments" -> Kind.PDF;
            default -> Kind.PNG;
        };
    }

    private static <T> ResponseEntity<T> json(HttpStatus status, T body) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
            .contentType(MediaType.APPLICATION_JSON).body(body);
    }

    /**
     * The bounded visible attachment list.
     *
     * @param items owner-scoped attachment records
     */
    public record Items(List<Attachment> items) {
    }

    /**
     * The current authorized text preview of the selected PDF.
     *
     * @param id attachment identifier
     * @param text canonical plain text
     * @param approximateText whether the text came from OCR
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TextPreview(String id, String text, Boolean approximateText) {

        @Override
        public String toString() {
            return "TextPreview[id=" + id + "]";
        }
    }
}

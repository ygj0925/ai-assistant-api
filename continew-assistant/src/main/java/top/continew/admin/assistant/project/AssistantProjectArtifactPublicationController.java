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

import cn.dev33.satoken.annotation.SaIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import com.feiniaojin.gracefulresponse.api.ExcludeFromGracefulResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import top.continew.admin.assistant.account.AssistantAccountController.ApiError;
import top.continew.admin.assistant.account.AssistantAccounts;
import top.continew.admin.assistant.account.AssistantApiException;
import top.continew.admin.assistant.artifact.AssistantArtifactImages;
import top.continew.admin.assistant.artifact.AssistantArtifacts.Download;
import top.continew.admin.assistant.artifact.AssistantArtifacts.PublicationSource;
import top.continew.admin.assistant.artifact.AssistantPresentation;
import top.continew.admin.assistant.artifact.AssistantSpreadsheet;
import top.continew.admin.assistant.attachment.AssistantAttachments.DownloadGrant;
import top.continew.admin.assistant.project.AssistantProjectArtifactComments.Comment;
import top.continew.admin.assistant.project.AssistantProjectArtifactPublications.Publication;
import top.continew.admin.assistant.project.AssistantProjectArtifactPublications.Summary;
import top.continew.admin.assistant.project.AssistantProjectCollaboration.Items;
import top.continew.starter.extension.tenant.annotation.TenantIgnore;
import top.continew.starter.log.annotation.Log;

import java.nio.charset.StandardCharsets;

/**
 * Exposes purpose-specific collaboration reads of approved immutable artifact versions.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
@RestController
@RequestMapping("/api/assistant/v1/collaboration/projects/{projectId}/artifact-publications")
@ConditionalOnProperty(name = "assistant.enabled", havingValue = "true")
@ExcludeFromGracefulResponse
@SaIgnore
@TenantIgnore
@Log(ignore = true)
public class AssistantProjectArtifactPublicationController {

    private static final String WORKSPACE = "X-Workspace-Id";

    private final AssistantAccounts accounts;
    private final AssistantProjectArtifactPublications service;
    private final AssistantProjectArtifactComments comments;

    /**
     * Wires protected collaboration operations.
     *
     * @param accounts consumer sessions
     * @param service scoped publication service
     * @param comments scoped comment service
     */
    public AssistantProjectArtifactPublicationController(AssistantAccounts accounts,
        AssistantProjectArtifactPublications service, AssistantProjectArtifactComments comments) {
        this.accounts = accounts;
        this.service = service;
        this.comments = comments;
    }

    /**
     * Publishes one reviewed immutable version only after affirmative consent.
     *
     * @param authorization consumer credentials
     * @param workspace current workspace
     * @param projectId collaboration project
     * @param input selected artifact and version with confirmation
     * @return new publication reference
     */
    @PostMapping
    public ResponseEntity<Summary> publish(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace,
        @PathVariable("projectId") String projectId,
        @RequestBody(required = false) JsonNode input) {
        var principal = accounts.authenticate(authorization, workspace);
        if (input == null || !input.isObject() || input.size() != 3
            || !input.path("artifactId").isTextual() || !input.path("versionId").isTextual()
            || !input.path("confirm").isBoolean()) {
            throw invalid();
        }
        return respond(HttpStatus.CREATED, service.publish(principal, projectId,
            input.path("artifactId").textValue(), input.path("versionId").textValue(),
            input.path("confirm").booleanValue()));
    }

    /**
     * Lists publications available to the current project member.
     *
     * @param authorization consumer credentials
     * @param workspace current workspace
     * @param projectId collaboration project
     * @return approved version headings
     */
    @GetMapping
    public ResponseEntity<Items<Summary>> list(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace,
        @PathVariable("projectId") String projectId) {
        return respond(HttpStatus.OK,
            service.list(accounts.authenticate(authorization, workspace), projectId));
    }

    /**
     * Returns one immutable published version.
     *
     * @param authorization consumer credentials
     * @param workspace current workspace
     * @param projectId collaboration project
     * @param id publication identifier
     * @return scoped original text, structured webpage or reviewed image metadata
     */
    @GetMapping("/{id}")
    public ResponseEntity<Publication> get(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace,
        @PathVariable("projectId") String projectId, @PathVariable("id") String id) {
        return respond(HttpStatus.OK,
            service.get(accounts.authenticate(authorization, workspace), projectId, id));
    }

    /**
     * Issues an image download grant for a current project collaborator.
     *
     * @param authorization consumer credentials
     * @param workspace current workspace
     * @param projectId collaboration project
     * @param id exact image publication identifier
     * @return short-lived authorization for one image and this device
     */
    @PostMapping("/{id}/download-grant")
    public ResponseEntity<DownloadGrant> imageGrant(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace,
        @PathVariable("projectId") String projectId, @PathVariable("id") String id) {
        var grant =
            service.imageGrant(accounts.authenticate(authorization, workspace), projectId, id);
        accounts.authenticate(authorization, workspace);
        return respond(HttpStatus.CREATED, grant);
    }

    /**
     * Returns the protected version as a fixed-name attachment.
     *
     * @param authorization consumer credentials
     * @param workspace current workspace
     * @param projectId collaboration project
     * @param id publication identifier
     * @param grant current one-use recipient authorization for PNG
     * @return protected attachment with the declared text, HTML, document or image format
     */
    @GetMapping("/{id}/download")
    public ResponseEntity<byte[]> download(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace,
        @PathVariable("projectId") String projectId, @PathVariable("id") String id,
        @RequestHeader(value = "X-Attachment-Grant", required = false) String grant) {
        Download file = service.download(accounts.authenticate(authorization, workspace),
            projectId, id, grant);
        accounts.authenticate(authorization, workspace);
        if (AssistantArtifactImages.FORMAT.equals(file.format())) {
            return document(file.bytes(), MediaType.IMAGE_PNG,
                ContentDisposition.attachment().filename("image.png").build(),
                "sandbox; default-src 'none'");
        }
        MediaType media = switch (file.format()) {
            case "webpage" -> new MediaType("text", "html", StandardCharsets.UTF_8);
            case "markdown" -> new MediaType("text", "markdown", StandardCharsets.UTF_8);
            case "sheet" -> MediaType.parseMediaType(AssistantSpreadsheet.MIME);
            case "slides" -> MediaType.parseMediaType(AssistantPresentation.MIME);
            default -> new MediaType("text", "plain", StandardCharsets.UTF_8);
        };
        String suffix = switch (file.format()) {
            case "webpage" -> ".html";
            case "markdown" -> ".md";
            case "sheet" -> ".xlsx";
            case "slides" -> ".pptx";
            case "code" -> file.codeSuffix();
            default -> ".txt";
        };
        var disposition = ContentDisposition.attachment()
            .filename("artifact" + suffix)
            .build();
        String policy =
            "webpage".equals(file.format()) ? PublicationSource.webpageResponsePolicy()
                : "sandbox; default-src 'none'";
        return document(file.bytes(), media, disposition, policy);
    }

    /**
     * Renders the explicitly approved webpage revision for the current project member.
     *
     * @param authorization consumer credentials
     * @param workspace current workspace
     * @param projectId collaboration project
     * @param id publication identifier
     * @return protected inline HTML
     */
    @GetMapping("/{id}/preview")
    public ResponseEntity<byte[]> preview(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace,
        @PathVariable("projectId") String projectId, @PathVariable("id") String id) {
        byte[] bytes =
            service.webpagePreview(accounts.authenticate(authorization, workspace), projectId, id);
        return document(bytes, new MediaType("text", "html", StandardCharsets.UTF_8),
            ContentDisposition.inline().build(), PublicationSource.webpageResponsePolicy());
    }

    /**
     * Durably retires a publication with current owner authority.
     *
     * @param authorization consumer credentials
     * @param workspace current workspace
     * @param projectId collaboration project
     * @param id publication identifier
     * @return non-cacheable success
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> revoke(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace,
        @PathVariable("projectId") String projectId, @PathVariable("id") String id) {
        service.revoke(accounts.authenticate(authorization, workspace), projectId, id);
        return respond(HttpStatus.NO_CONTENT, null);
    }

    /**
     * Lists visible comments on an exact immutable publication.
     *
     * @param authorization consumer credentials
     * @param workspace current workspace
     * @param projectId collaboration project
     * @param id scoped publication identifier
     * @return visible comments with role-bound controls
     */
    @GetMapping("/{id}/comments")
    public ResponseEntity<Items<Comment>> comments(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace,
        @PathVariable("projectId") String projectId, @PathVariable("id") String id) {
        return respond(HttpStatus.OK, comments.list(accounts.authenticate(authorization, workspace),
            projectId, id));
    }

    /**
     * Creates the confirmed text under a current owner or editor role.
     *
     * @param authorization consumer credentials
     * @param workspace current workspace
     * @param key client retry UUID
     * @param projectId collaboration project
     * @param id scoped publication identifier
     * @param input explicitly entered comment body
     * @return exact created or replayed comment
     */
    @PostMapping("/{id}/comments")
    public ResponseEntity<Comment> createComment(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace,
        @RequestHeader(value = "Idempotency-Key", required = false) String key,
        @PathVariable("projectId") String projectId, @PathVariable("id") String id,
        @RequestBody(required = false) JsonNode input) {
        var principal = accounts.authenticate(authorization, workspace);
        if (input == null || !input.isObject() || input.size() != 1
            || !input.path("content").isTextual()) {
            throw invalid();
        }
        return respond(HttpStatus.CREATED, comments.create(principal, projectId, id, key,
            input.path("content").textValue()));
    }

    /**
     * Deletes a comment under its writer or owner scope.
     *
     * @param authorization consumer credentials
     * @param workspace current workspace
     * @param projectId collaboration project
     * @param id scoped publication identifier
     * @param commentId scoped comment identifier
     * @return non-cacheable success
     */
    @DeleteMapping("/{id}/comments/{commentId}")
    public ResponseEntity<Void> deleteComment(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace,
        @PathVariable("projectId") String projectId, @PathVariable("id") String id,
        @PathVariable("commentId") String commentId) {
        comments.delete(accounts.authenticate(authorization, workspace), projectId, id, commentId);
        return respond(HttpStatus.NO_CONTENT, null);
    }

    /**
     * Returns sanitized and non-cacheable authorization or validation errors.
     *
     * @param error public failure
     * @return safe API response
     */
    @ExceptionHandler(AssistantApiException.class)
    public ResponseEntity<ApiError> handle(AssistantApiException error) {
        return failure(error.status(), new ApiError(error.code(), error.getMessage()));
    }

    /**
     * Rejects malformed JSON safely.
     *
     * @return non-cacheable validation failure
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> malformed() {
        return handle(invalid());
    }

    /**
     * Conceals datastore and transaction details.
     *
     * @return temporary internal service failure
     */
    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    public ResponseEntity<ApiError> unavailable() {
        return failure(HttpStatus.SERVICE_UNAVAILABLE,
            new ApiError("INTERNAL_ERROR",
                "Project artifact publications are temporarily unavailable."));
    }

    private static ResponseEntity<ApiError> failure(HttpStatus status, ApiError error) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
            .cacheControl(CacheControl.noStore()).body(error);
    }

    private static ResponseEntity<byte[]> document(byte[] bytes, MediaType media,
        ContentDisposition disposition,
        String policy) {
        return ResponseEntity.ok().contentType(media).contentLength(bytes.length)
            .cacheControl(CacheControl.noStore())
            .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
            .header("X-Content-Type-Options", "nosniff")
            .header("Content-Security-Policy", policy)
            .body(bytes);
    }

    private static AssistantApiException invalid() {
        return new AssistantApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
            "Invalid project artifact publication request.");
    }

    private static <T> ResponseEntity<T> respond(HttpStatus status, T content) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(content);
    }
}

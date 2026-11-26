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

import cn.dev33.satoken.annotation.SaIgnore;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import top.continew.admin.assistant.account.AssistantAccountController.ApiError;
import top.continew.admin.assistant.account.AssistantAccounts;
import top.continew.admin.assistant.account.AssistantAccounts.User;
import top.continew.admin.assistant.account.AssistantApiException;
import top.continew.admin.assistant.artifact.AssistantArtifacts.Artifact;
import top.continew.admin.assistant.artifact.AssistantArtifacts.Create;
import top.continew.admin.assistant.artifact.AssistantArtifacts.Page;
import top.continew.admin.assistant.artifact.AssistantArtifacts.Summary;
import top.continew.admin.assistant.artifact.AssistantArtifacts.Version;
import top.continew.admin.assistant.artifact.AssistantArtifacts.VersionSummary;
import top.continew.admin.assistant.artifact.AssistantArtifacts.WriteBody;
import top.continew.admin.assistant.attachment.AssistantAttachments.DownloadGrant;
import top.continew.starter.extension.tenant.annotation.TenantIgnore;
import top.continew.starter.log.annotation.Log;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Authenticated drafts and protected text or workbook downloads.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
@RestController
@RequestMapping("/api/assistant/v1/artifacts")
@ConditionalOnProperty(name = "assistant.enabled", havingValue = "true")
@ExcludeFromGracefulResponse
@SaIgnore
@TenantIgnore
@Log(ignore = true)
public class AssistantArtifactController {

    private static final String WORKSPACE = "X-Workspace-Id";
    private static final int MAX_REQUEST_BYTES = 200_000;
    private static final MediaType HTML = new MediaType("text", "html", StandardCharsets.UTF_8);

    private final AssistantAccounts accounts;
    private final AssistantArtifacts artifacts;
    private final ObjectReader requestReader;

    /**
     * Wires the consumer authorization boundary and private drafts.
     *
     * @param accounts consumer sessions and workspaces
     * @param artifacts private draft service
     * @param mapper application JSON configuration
     */
    public AssistantArtifactController(AssistantAccounts accounts, AssistantArtifacts artifacts,
        ObjectMapper mapper) {
        this.accounts = accounts;
        this.artifacts = artifacts;
        this.requestReader = AssistantArtifacts.jsonReader(mapper);
    }

    /**
     * Creates an authored or reply-derived draft.
     *
     * @param authorization consumer bearer token
     * @param workspace selected workspace
     * @param key stable request UUID
     * @param request request containing exact JSON decimal tokens
     * @return new private artifact
     */
    @PostMapping
    public ResponseEntity<Artifact> create(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace,
        @RequestHeader(value = "Idempotency-Key", required = false) String key,
        HttpServletRequest request) {
        User owner = owner(authorization, workspace);
        JsonNode body = exactObject(request);
        String project = optional(body, "projectId");
        String source = optional(body, "sourceMessageId");
        JsonNode confirmation = body.get("confirmSource");
        if (confirmation != null && !confirmation.isBoolean()) {
            throw invalid();
        }
        WriteBody input = writeBody(body);
        fields(body, 2 + (project == null ? 0 : 1) + (source == null ? 0 : 1)
            + (confirmation == null ? 0 : 1) + input.fieldCount());
        Artifact result = artifacts.create(owner, key,
            new Create(project, source, field(body, "title"), field(body, "format"), input,
                confirmation == null ? null : confirmation.booleanValue()));
        owner(authorization, workspace);
        return response(HttpStatus.CREATED, result);
    }

    /**
     * Lists the caller's private draft metadata.
     *
     * @param authorization consumer bearer token
     * @param workspace selected workspace
     * @param projectId optional owned project filter
     * @param limit optional page size
     * @param cursor scoped continuation
     * @return metadata page
     */
    @GetMapping
    public ResponseEntity<Page<Summary>> list(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace,
        @RequestParam(value = "projectId", required = false) String projectId,
        @RequestParam(value = "limit", required = false) Integer limit,
        @RequestParam(value = "cursor", required = false) String cursor) {
        Page<Summary> result =
            artifacts.list(owner(authorization, workspace), projectId, limit, cursor);
        owner(authorization, workspace);
        return response(HttpStatus.OK, result);
    }

    /**
     * Reads one private draft and current version.
     *
     * @param authorization consumer bearer token
     * @param workspace selected workspace
     * @param id artifact identifier
     * @return current artifact
     */
    @GetMapping("/{artifactId}")
    public ResponseEntity<Artifact> get(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace,
        @PathVariable("artifactId") String id) {
        Artifact result = artifacts.get(owner(authorization, workspace), id);
        owner(authorization, workspace);
        return response(HttpStatus.OK, result);
    }

    /**
     * Lists bounded immutable version metadata.
     *
     * @param authorization consumer bearer token
     * @param workspace selected workspace
     * @param id artifact identifier
     * @param limit optional page size
     * @param cursor scoped continuation
     * @return private version list
     */
    @GetMapping("/{artifactId}/versions")
    public ResponseEntity<Page<VersionSummary>> versions(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace,
        @PathVariable("artifactId") String id,
        @RequestParam(value = "limit", required = false) Integer limit,
        @RequestParam(value = "cursor", required = false) String cursor) {
        Page<VersionSummary> result =
            artifacts.versions(owner(authorization, workspace), id, limit, cursor);
        owner(authorization, workspace);
        return response(HttpStatus.OK, result);
    }

    /**
     * Reads an exact version under current ownership.
     *
     * @param authorization consumer bearer token
     * @param workspace selected workspace
     * @param id artifact identifier
     * @param version immutable version identifier
     * @return version and original content
     */
    @GetMapping("/{artifactId}/versions/{versionId}")
    public ResponseEntity<Version> version(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace,
        @PathVariable("artifactId") String id,
        @PathVariable("versionId") String version) {
        Version result = artifacts.version(owner(authorization, workspace), id, version);
        owner(authorization, workspace);
        return response(HttpStatus.OK, result);
    }

    /**
     * Issues one download authorization for an exact approved image version.
     *
     * @param authorization consumer bearer token
     * @param workspace selected workspace
     * @param id owned image artifact identifier
     * @param version approved immutable version identifier
     * @return short-lived token bound to the current device session
     */
    @PostMapping("/{artifactId}/versions/{versionId}/download-grant")
    public ResponseEntity<DownloadGrant> imageGrant(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace,
        @PathVariable("artifactId") String id,
        @PathVariable("versionId") String version) {
        var result =
            artifacts.imageGrant(accounts.authenticate(authorization, workspace), id, version);
        owner(authorization, workspace);
        return response(HttpStatus.CREATED, result);
    }

    /**
     * Downloads exact bytes under renewed authorization and a fixed safe filename.
     *
     * @param authorization consumer bearer token
     * @param workspace selected workspace
     * @param id artifact identifier
     * @param version immutable version identifier
     * @param grant current one-use token for image versions
     * @return protected attachment
     */
    @GetMapping("/{artifactId}/versions/{versionId}/download")
    public ResponseEntity<byte[]> download(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace,
        @PathVariable("artifactId") String id,
        @PathVariable("versionId") String version,
        @RequestHeader(value = "X-Attachment-Grant", required = false) String grant) {
        var file =
            artifacts.download(accounts.authenticate(authorization, workspace), id, version, grant);
        owner(authorization, workspace);
        MediaType media;
        String extension;
        String policy = "sandbox; default-src 'none'";
        switch (file.format()) {
            case AssistantWebpage.FORMAT -> {
                media = HTML;
                extension = ".html";
                policy = AssistantWebpage.RESPONSE_POLICY;
            }
            case AssistantPresentation.FORMAT -> {
                media = MediaType.parseMediaType(AssistantPresentation.MIME);
                extension = ".pptx";
            }
            case AssistantSpreadsheet.FORMAT -> {
                media = MediaType.parseMediaType(AssistantSpreadsheet.MIME);
                extension = ".xlsx";
            }
            case AssistantArtifactImages.FORMAT -> {
                return document(file.bytes(), MediaType.IMAGE_PNG,
                    ContentDisposition.attachment().filename("image.png").build(), policy);
            }
            case "markdown" -> {
                media = new MediaType("text", "markdown", StandardCharsets.UTF_8);
                extension = ".md";
            }
            default -> {
                media = new MediaType("text", "plain", StandardCharsets.UTF_8);
                extension = file.codeSuffix() == null ? ".txt" : file.codeSuffix();
            }
        }
        var disposition = ContentDisposition.attachment()
            .filename("artifact-" + file.version() + extension)
            .build();
        return document(file.bytes(), media, disposition, policy);
    }

    /**
     * Renders an authenticated inline snapshot of an exact private webpage version.
     *
     * @param authorization consumer bearer token
     * @param workspace selected workspace
     * @param id webpage artifact identifier
     * @param version immutable webpage version identifier
     * @return isolated HTML with a restrictive response policy
     */
    @GetMapping("/{artifactId}/versions/{versionId}/preview")
    public ResponseEntity<byte[]> preview(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace,
        @PathVariable("artifactId") String id,
        @PathVariable("versionId") String version) {
        byte[] bytes = artifacts.webpagePreview(owner(authorization, workspace), id, version);
        owner(authorization, workspace);
        return document(bytes, HTML, ContentDisposition.inline().build(),
            AssistantWebpage.RESPONSE_POLICY);
    }

    /**
     * Saves one immutable revision using compare-and-swap against the observed head.
     *
     * @param authorization consumer bearer token
     * @param workspace selected workspace
     * @param id artifact identifier
     * @param request request containing the expected head and exact JSON decimal tokens
     * @return updated head
     */
    @PostMapping("/{artifactId}/versions")
    public ResponseEntity<Artifact> revise(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace,
        @PathVariable("artifactId") String id,
        HttpServletRequest request) {
        User owner = owner(authorization, workspace);
        JsonNode body = exactObject(request);
        fields(body, 3);
        Artifact result = artifacts.revise(owner, id,
            field(body, "baseVersionId"), field(body, "title"), writeBody(body));
        owner(authorization, workspace);
        return response(HttpStatus.CREATED, result);
    }

    /**
     * Restores a copy of an earlier owned version as a new immutable head.
     *
     * @param authorization consumer bearer token
     * @param workspace selected workspace
     * @param id artifact identifier
     * @param body expected head and explicitly selected origin
     * @return rollback version
     */
    @PostMapping("/{artifactId}/rollback")
    public ResponseEntity<Artifact> rollback(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace,
        @PathVariable("artifactId") String id,
        @RequestBody(required = false) JsonNode body) {
        User owner = owner(authorization, workspace);
        object(body);
        fields(body, 2);
        Artifact result =
            artifacts.rollback(owner, id, field(body, "baseVersionId"), field(body, "versionId"));
        owner(authorization, workspace);
        return response(HttpStatus.CREATED, result);
    }

    /**
     * Durably revokes a private draft and all retained versions.
     *
     * @param authorization consumer bearer token
     * @param workspace selected workspace
     * @param id artifact identifier
     * @return empty response
     */
    @DeleteMapping("/{artifactId}")
    public ResponseEntity<Void> delete(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace,
        @PathVariable("artifactId") String id) {
        artifacts.delete(owner(authorization, workspace), id);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    /**
     * Returns an expected failure without exposing private text.
     *
     * @param error public API failure
     * @return sanitized response body
     */
    @ExceptionHandler(AssistantApiException.class)
    public ResponseEntity<ApiError> handle(AssistantApiException error) {
        return response(error.status(), new ApiError(error.code(), error.getMessage()));
    }

    /**
     * Rejects malformed fields and query parameters.
     *
     * @return sanitized validation error
     */
    @ExceptionHandler({HttpMessageNotReadableException.class,
        MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiError> malformed() {
        return handle(invalid());
    }

    /**
     * Hides backend storage and transaction details.
     *
     * @return sanitized unavailability error
     */
    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    public ResponseEntity<ApiError> unavailable() {
        return response(HttpStatus.SERVICE_UNAVAILABLE,
            new ApiError("CAPABILITY_UNAVAILABLE",
                "Private artifacts are temporarily unavailable."));
    }

    private User owner(String authorization, String workspace) {
        return accounts.authenticate(authorization, workspace).user();
    }

    private JsonNode exactObject(HttpServletRequest request) {
        try {
            byte[] body = request.getInputStream().readNBytes(MAX_REQUEST_BYTES + 1);
            if (body.length > MAX_REQUEST_BYTES) {
                throw new AssistantApiException(HttpStatus.PAYLOAD_TOO_LARGE, "VALIDATION_ERROR",
                    "The encoded artifact request is too large.");
            }
            JsonNode decoded = requestReader.readTree(body);
            object(decoded);
            return decoded;
        } catch (IOException failure) {
            throw invalid();
        }
    }

    private static void object(JsonNode body) {
        if (body == null || !body.isObject()) {
            throw invalid();
        }
    }

    private static void fields(JsonNode body, int count) {
        if (body.size() != count) {
            throw invalid();
        }
    }

    private static String optional(JsonNode body, String name) {
        return body.has(name) ? field(body, name) : null;
    }

    private static WriteBody writeBody(JsonNode body) {
        return new WriteBody(optional(body, "content"), optionalObject(body, "sheet"),
            optionalObject(body, "presentation"), optionalObject(body, "code"),
            optionalObject(body, "website"), optionalObject(body, "image"));
    }

    private static JsonNode optionalObject(JsonNode body, String field) {
        JsonNode value = body.get(field);
        if (value != null && !value.isObject()) {
            throw invalid();
        }
        return value;
    }

    private static String field(JsonNode body, String name) {
        if (!body.path(name).isTextual()) {
            throw invalid();
        }
        return body.path(name).textValue();
    }

    private static AssistantApiException invalid() {
        return new AssistantApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
            "The private artifact request is invalid.");
    }

    private static <T> ResponseEntity<T> response(HttpStatus status, T value) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
            .cacheControl(CacheControl.noStore()).body(value);
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
}

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
import top.continew.admin.assistant.project.AssistantProjectCollaboration.Items;
import top.continew.admin.assistant.project.AssistantSharedDrafts.Draft;
import top.continew.admin.assistant.project.AssistantSharedDrafts.Revision;
import top.continew.admin.assistant.project.AssistantSharedDrafts.RevisionSummary;
import top.continew.admin.assistant.project.AssistantSharedDrafts.Summary;
import top.continew.admin.assistant.project.AssistantSharedDrafts.Write;
import top.continew.starter.extension.tenant.annotation.TenantIgnore;
import top.continew.starter.log.annotation.Log;

import java.nio.charset.StandardCharsets;

/**
 * Exposes shared Markdown revisions in their explicit collaboration scope.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
@RestController
@RequestMapping("/api/assistant/v1/collaboration/projects/{projectId}/shared-drafts")
@ConditionalOnProperty(name = "assistant.enabled", havingValue = "true")
@ExcludeFromGracefulResponse
@SaIgnore
@TenantIgnore
@Log(ignore = true)
public class AssistantSharedDraftController {

    private static final String WORKSPACE = "X-Workspace-Id";

    private final AssistantAccounts accounts;
    private final AssistantSharedDrafts service;

    /**
     * Wires consumer authentication to shared draft revisions.
     *
     * @param accounts consumer sessions
     * @param service scoped shared draft storage
     */
    public AssistantSharedDraftController(AssistantAccounts accounts,
        AssistantSharedDrafts service) {
        this.accounts = accounts;
        this.service = service;
    }

    /**
     * Creates an expressly shared Markdown draft after consent.
     *
     * @param authorization consumer credentials
     * @param workspace current workspace
     * @param projectId scoped identifier
     * @param input submitted data
     * @return non-cacheable response
     */
    @PostMapping
    public ResponseEntity<Draft> create(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace,
        @PathVariable("projectId") String projectId,
        @RequestBody(required = false) JsonNode input) {
        var actor = accounts.authenticate(authorization, workspace);
        requireFields(input, 3);
        if (!input.path("confirm").isBoolean()) {
            throw invalid();
        }
        return respond(HttpStatus.CREATED, service.create(actor, projectId, write(input),
            input.path("confirm").booleanValue()));
    }

    /**
     * Lists authorized project draft headings.
     *
     * @param authorization consumer credentials
     * @param workspace current workspace
     * @param projectId scoped identifier
     * @return non-cacheable response
     */
    @GetMapping
    public ResponseEntity<Items<Summary>> list(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace,
        @PathVariable("projectId") String projectId) {
        var actor = accounts.authenticate(authorization, workspace);
        return respond(HttpStatus.OK, service.list(actor, projectId));
    }

    /**
     * Returns the current immutable shared draft head.
     *
     * @param authorization consumer credentials
     * @param workspace current workspace
     * @param projectId scoped identifier
     * @param id scoped identifier
     * @return non-cacheable response
     */
    @GetMapping("/{id}")
    public ResponseEntity<Draft> get(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace,
        @PathVariable("projectId") String projectId,
        @PathVariable("id") String id) {
        var actor = accounts.authenticate(authorization, workspace);
        return respond(HttpStatus.OK, service.get(actor, projectId, id));
    }

    /**
     * Durably removes one shared draft and its history.
     *
     * @param authorization consumer credentials
     * @param workspace current workspace
     * @param projectId scoped identifier
     * @param id scoped identifier
     * @return non-cacheable response
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace,
        @PathVariable("projectId") String projectId,
        @PathVariable("id") String id) {
        var actor = accounts.authenticate(authorization, workspace);
        service.delete(actor, projectId, id);
        return respond(HttpStatus.NO_CONTENT, null);
    }

    /**
     * Lists immutable revision headings and actor-relative authorship.
     *
     * @param authorization consumer credentials
     * @param workspace current workspace
     * @param projectId scoped identifier
     * @param id scoped identifier
     * @return non-cacheable response
     */
    @GetMapping("/{id}/revisions")
    public ResponseEntity<Items<RevisionSummary>> history(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace,
        @PathVariable("projectId") String projectId,
        @PathVariable("id") String id) {
        var actor = accounts.authenticate(authorization, workspace);
        return respond(HttpStatus.OK, service.history(actor, projectId, id));
    }

    /**
     * Appends a revision when its base is still current.
     *
     * @param authorization consumer credentials
     * @param workspace current workspace
     * @param projectId scoped identifier
     * @param id scoped identifier
     * @param input submitted data
     * @return non-cacheable response
     */
    @PostMapping("/{id}/revisions")
    public ResponseEntity<Draft> revise(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace,
        @PathVariable("projectId") String projectId,
        @PathVariable("id") String id,
        @RequestBody(required = false) JsonNode input) {
        var actor = accounts.authenticate(authorization, workspace);
        requireFields(input, 3);
        return respond(HttpStatus.CREATED, service.revise(actor, projectId, id,
            field(input, "baseRevisionId"), write(input)));
    }

    /**
     * Appends a new revision from a selected authorized earlier version.
     *
     * @param authorization consumer credentials
     * @param workspace current workspace
     * @param projectId scoped identifier
     * @param id scoped identifier
     * @param input submitted data
     * @return non-cacheable response
     */
    @PostMapping("/{id}/rollback")
    public ResponseEntity<Draft> rollback(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace,
        @PathVariable("projectId") String projectId,
        @PathVariable("id") String id,
        @RequestBody(required = false) JsonNode input) {
        var actor = accounts.authenticate(authorization, workspace);
        requireFields(input, 2);
        return respond(HttpStatus.CREATED, service.rollback(actor, projectId, id,
            field(input, "baseRevisionId"), field(input, "revisionId")));
    }

    /**
     * Reads the exact authorized Markdown revision.
     *
     * @param authorization consumer credentials
     * @param workspace current workspace
     * @param projectId scoped identifier
     * @param id scoped identifier
     * @param revisionId scoped identifier
     * @return non-cacheable response
     */
    @GetMapping("/{id}/revisions/{revisionId}")
    public ResponseEntity<Revision> revision(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace,
        @PathVariable("projectId") String projectId,
        @PathVariable("id") String id,
        @PathVariable("revisionId") String revisionId) {
        var actor = accounts.authenticate(authorization, workspace);
        return respond(HttpStatus.OK, service.getRevision(actor, projectId, id, revisionId));
    }

    /**
     * Downloads exact Markdown bytes through current project authority.
     *
     * @param authorization consumer credentials
     * @param workspace current workspace
     * @param projectId scoped identifier
     * @param id scoped identifier
     * @param revisionId scoped identifier
     * @return non-cacheable response
     */
    @GetMapping("/{id}/revisions/{revisionId}/download")
    public ResponseEntity<byte[]> download(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace,
        @PathVariable("projectId") String projectId,
        @PathVariable("id") String id,
        @PathVariable("revisionId") String revisionId) {
        var actor = accounts.authenticate(authorization, workspace);
        Revision revision = service.getRevision(actor, projectId, id, revisionId);
        byte[] bytes = revision.content().getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
            .contentType(new MediaType("text", "markdown", StandardCharsets.UTF_8))
            .contentLength(bytes.length).cacheControl(CacheControl.noStore())
            .header(HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename("shared-draft.md").build().toString())
            .header("X-Content-Type-Options", "nosniff")
            .header("Content-Security-Policy", "sandbox; default-src 'none'")
            .body(bytes);
    }

    /**
     * Preserves the typed status even for download clients accepting only Markdown.
     *
     * @param error safe caller-visible error
     * @return non-cacheable failure
     */
    @ExceptionHandler(AssistantApiException.class)
    public ResponseEntity<ApiError> handle(AssistantApiException error) {
        return failure(error.status(), new ApiError(error.code(), error.getMessage()));
    }

    /**
     * Rejects a malformed request safely.
     *
     * @return typed validation failure
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> malformed() {
        return handle(invalid());
    }

    /**
     * Conceals low-level database failures.
     *
     * @return temporary internal failure
     */
    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    public ResponseEntity<ApiError> unavailable() {
        return failure(HttpStatus.SERVICE_UNAVAILABLE,
            new ApiError("INTERNAL_ERROR", "Shared project drafts are temporarily unavailable."));
    }

    private static void requireFields(JsonNode input, int expected) {
        if (input == null || !input.isObject() || input.size() != expected) {
            throw invalid();
        }
    }

    private static Write write(JsonNode input) {
        return new Write(field(input, "title"), field(input, "content"));
    }

    private static String field(JsonNode input, String name) {
        JsonNode value = input.path(name);
        if (!value.isTextual()) {
            throw invalid();
        }
        return value.textValue();
    }

    private static AssistantApiException invalid() {
        return new AssistantApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
            "Invalid shared project draft request.");
    }

    private static ResponseEntity<ApiError> failure(HttpStatus status, ApiError error) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
            .cacheControl(CacheControl.noStore()).body(error);
    }

    private static <T> ResponseEntity<T> respond(HttpStatus status, T data) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(data);
    }
}

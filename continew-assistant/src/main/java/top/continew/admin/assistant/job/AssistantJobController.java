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

package top.continew.admin.assistant.job;

import cn.dev33.satoken.annotation.SaIgnore;
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
import top.continew.admin.assistant.account.AssistantApiException;
import top.continew.admin.assistant.job.AssistantJobs.Job;
import top.continew.admin.assistant.job.AssistantJobs.Page;
import top.continew.starter.extension.tenant.annotation.TenantIgnore;
import top.continew.starter.log.annotation.Log;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Exposes scoped background export status and artifacts to consumers.
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
public class AssistantJobController {

    private static final String WORKSPACE = "X-Workspace-Id";
    private static final MediaType MARKDOWN =
        new MediaType("text", "markdown", StandardCharsets.UTF_8);
    private static final String ATTACHMENT =
        ContentDisposition.attachment().filename("conversation-export.md").build().toString();

    private final AssistantAccounts accounts;
    private final Optional<AssistantJobs> jobs;

    /**
     * Connects endpoints to the authenticated job service.
     *
     * @param accounts consumer account and workspace access
     * @param jobs durable job store when background jobs are enabled
     */
    public AssistantJobController(AssistantAccounts accounts, Optional<AssistantJobs> jobs) {
        this.accounts = accounts;
        this.jobs = jobs;
    }

    /**
     * Enqueues an export for the chosen branch.
     *
     * @param authorization consumer session
     * @param workspace selected workspace
     * @param conversationId source conversation
     * @param key idempotency key
     * @param input requested branch anchor
     * @return original or new job
     */
    @PostMapping("/conversations/{conversationId}/exports")
    public ResponseEntity<Job> start(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace,
        @PathVariable("conversationId") String conversationId,
        @RequestHeader(value = "Idempotency-Key", required = false) String key,
        @RequestBody(required = false) ExportRequest input) {
        var user = accounts.authenticate(authorization, workspace);
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore())
            .body(availableJobs().start(user, conversationId,
                input == null ? null : input.sourceMessageId(),
                key));
    }

    /**
     * Reads background jobs for the current account and workspace.
     *
     * @param authorization consumer session
     * @param workspace selected workspace
     * @param cursor opaque continuation
     * @param limit page size
     * @return visible jobs
     */
    @GetMapping("/jobs")
    public ResponseEntity<Page> list(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace,
        @RequestParam(value = "cursor", required = false) String cursor,
        @RequestParam(value = "limit", defaultValue = "25") int limit) {
        var user = accounts.authenticate(authorization, workspace);
        return ok(availableJobs().list(user, cursor, limit));
    }

    /**
     * Reads one owned job.
     *
     * @param authorization consumer session
     * @param workspace selected workspace
     * @param id job ID
     * @return authoritative state and progress
     */
    @GetMapping("/jobs/{id}")
    public ResponseEntity<Job> get(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace,
        @PathVariable("id") String id) {
        var user = accounts.authenticate(authorization, workspace);
        return ok(availableJobs().get(user, id));
    }

    /**
     * Cancels an active job without overwriting a prior terminal outcome.
     *
     * @param authorization consumer session
     * @param workspace selected workspace
     * @param id job ID
     * @return authoritative state
     */
    @PostMapping("/jobs/{id}/cancel")
    public ResponseEntity<Job> cancel(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace,
        @PathVariable("id") String id) {
        var user = accounts.authenticate(authorization, workspace);
        return ok(availableJobs().cancel(user, id));
    }

    /**
     * Downloads an owned completed export as UTF-8 Markdown.
     *
     * @param authorization consumer session
     * @param workspace selected workspace
     * @param id completed job ID
     * @return authenticated download
     */
    @GetMapping("/jobs/{id}/artifact")
    public ResponseEntity<byte[]> artifact(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace,
        @PathVariable("id") String id) {
        var user = accounts.authenticate(authorization, workspace);
        byte[] content = availableJobs().artifact(user, id);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MARKDOWN)
            .header(HttpHeaders.CONTENT_DISPOSITION, ATTACHMENT)
            .header("X-Content-Type-Options", "nosniff").contentLength(content.length)
            .body(content);
    }

    /**
     * Sends a bounded consumer failure without inserting data into the response cache.
     *
     * @param error known consumer error
     * @return plain error envelope
     */
    @ExceptionHandler(AssistantApiException.class)
    public ResponseEntity<ApiError> failure(AssistantApiException error) {
        return ResponseEntity.status(error.status()).cacheControl(CacheControl.noStore())
            .contentType(MediaType.APPLICATION_JSON)
            .body(new ApiError(error.code(), error.getMessage()));
    }

    /**
     * Validates malformed parameters before they enter a job transaction.
     *
     * @return validation error
     */
    @ExceptionHandler({HttpMessageNotReadableException.class,
        MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiError> invalid() {
        return failure(new AssistantApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
            "The job request is invalid."));
    }

    /**
     * Keeps internal database details out of consumer responses.
     *
     * @return retryable service error
     */
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ApiError> storage() {
        return failure(
            new AssistantApiException(HttpStatus.SERVICE_UNAVAILABLE, "PROVIDER_UNAVAILABLE",
                "The background job store is temporarily unavailable."));
    }

    private AssistantJobs availableJobs() {
        return jobs.orElseThrow(
            () -> new AssistantApiException(HttpStatus.FORBIDDEN, "CAPABILITY_UNAVAILABLE",
                "Background jobs are unavailable."));
    }

    private static <T> ResponseEntity<T> ok(T value) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value);
    }

    /**
     * Identifies the last message in a selected immutable branch.
     *
     * @param sourceMessageId message ID
     */
    public record ExportRequest(String sourceMessageId) {
    }
}

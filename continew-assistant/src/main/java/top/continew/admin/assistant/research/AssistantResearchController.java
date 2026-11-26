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

package top.continew.admin.assistant.research;

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
import top.continew.admin.assistant.account.AssistantAccountController.ApiError;
import top.continew.admin.assistant.account.AssistantAccounts;
import top.continew.admin.assistant.account.AssistantApiException;
import top.continew.admin.assistant.research.AssistantResearch.Page;
import top.continew.admin.assistant.research.AssistantResearch.Task;
import top.continew.admin.assistant.retrieval.AssistantRetrieval.ResearchSource;
import top.continew.starter.extension.tenant.annotation.TenantIgnore;
import top.continew.starter.log.annotation.Log;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Offers privately approved, offline-only research plans and reports.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
@RestController
@RequestMapping("/api/assistant/v1/research")
@ConditionalOnProperty(name = "assistant.enabled", havingValue = "true")
@ExcludeFromGracefulResponse
@SaIgnore
@TenantIgnore
@Log(ignore = true)
public class AssistantResearchController {

    private static final String WORKSPACE = "X-Workspace-Id";
    private static final MediaType REPORT = new MediaType("text", "plain", StandardCharsets.UTF_8);
    private static final String ATTACHMENT = ContentDisposition.attachment()
        .filename("research-report.txt").build().toString();

    private final AssistantAccounts accounts;
    private final Optional<AssistantResearch> research;

    /**
     * Wires consumer authentication and optionally enabled research.
     *
     * @param accounts consumer accounts
     * @param research registered research service
     */
    public AssistantResearchController(AssistantAccounts accounts,
        Optional<AssistantResearch> research) {
        this.accounts = accounts;
        this.research = research;
    }

    /**
     * Lists eligible catalogue sources for review and explicit selection.
     *
     * @param authorization consumer authorization
     * @param workspace selected workspace
     * @param cursor source continuation
     * @param limit requested page size
     * @return safe source metadata
     */
    @GetMapping("/sources")
    public ResponseEntity<Page<ResearchSource>> sources(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace,
        @RequestParam(value = "cursor", required = false) String cursor,
        @RequestParam(value = "limit", defaultValue = "25") int limit) {
        var user = accounts.authenticate(authorization, workspace).user();
        return response(HttpStatus.OK, service().sources(user, cursor, limit));
    }

    /**
     * Starts a private plan that still requires owner approval.
     *
     * @param authorization consumer authorization
     * @param workspace selected workspace
     * @param key idempotency key
     * @param body explicit question and source IDs
     * @return current state of the created task
     */
    @PostMapping
    public ResponseEntity<Task> create(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace,
        @RequestHeader(value = "Idempotency-Key", required = false) String key,
        @RequestBody(required = false) JsonNode body) {
        var user = accounts.authenticate(authorization, workspace).user();
        var service = service();
        Plan plan = plan(body, 2);
        return response(HttpStatus.CREATED, service.create(user, key, plan.question(),
            plan.sources()));
    }

    /**
     * Lists tasks belonging to the authenticated workspace account.
     *
     * @param authorization consumer authorization
     * @param workspace selected workspace
     * @param cursor task continuation
     * @param limit requested page size
     * @return visible task page
     */
    @GetMapping
    public ResponseEntity<Page<Task>> list(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace,
        @RequestParam(value = "cursor", required = false) String cursor,
        @RequestParam(value = "limit", defaultValue = "25") int limit) {
        var user = accounts.authenticate(authorization, workspace).user();
        return response(HttpStatus.OK, service().list(user, cursor, limit));
    }

    /**
     * Reads the latest revision and its checkpoint progress.
     *
     * @param authorization consumer authorization
     * @param workspace selected workspace
     * @param id task identifier
     * @return current task
     */
    @GetMapping("/{id}")
    public ResponseEntity<Task> get(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace,
        @PathVariable("id") String id) {
        var user = accounts.authenticate(authorization, workspace).user();
        return response(HttpStatus.OK, service().get(user, id));
    }

    /**
     * Revises the plan and withdraws its previous approval when it changes.
     *
     * @param authorization consumer authorization
     * @param workspace selected workspace
     * @param id task identifier
     * @param body expected revision and amended plan
     * @return latest task
     */
    @PatchMapping("/{id}")
    public ResponseEntity<Task> revise(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace,
        @PathVariable("id") String id,
        @RequestBody(required = false) JsonNode body) {
        var user = accounts.authenticate(authorization, workspace).user();
        var service = service();
        Plan plan = plan(body, 3);
        return response(HttpStatus.OK, service.revise(user, id, revision(body, "expectedRevision"),
            plan.question(), plan.sources()));
    }

    /**
     * Explicitly approves a single reviewed version.
     *
     * @param authorization consumer authorization
     * @param workspace selected workspace
     * @param id task identifier
     * @param body reviewed revision
     * @return durable queued task
     */
    @PostMapping("/{id}/approve")
    public ResponseEntity<Task> approve(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace,
        @PathVariable("id") String id,
        @RequestBody(required = false) JsonNode body) {
        var user = accounts.authenticate(authorization, workspace).user();
        var service = service();
        requireObject(body, 1);
        return response(HttpStatus.ACCEPTED, service.approve(user, id, revision(body, "revision")));
    }

    /**
     * Cancels a task version and retires its approval.
     *
     * @param authorization consumer authorization
     * @param workspace selected workspace
     * @param id task identifier
     * @param body current reviewed revision
     * @return cancelled task
     */
    @PostMapping("/{id}/cancel")
    public ResponseEntity<Task> cancel(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace,
        @PathVariable("id") String id,
        @RequestBody(required = false) JsonNode body) {
        var user = accounts.authenticate(authorization, workspace).user();
        var service = service();
        requireObject(body, 1);
        return response(HttpStatus.OK, service.cancel(user, id, revision(body, "revision")));
    }

    /**
     * Downloads a bounded plain-text report when its approval and evidence remain available.
     *
     * @param authorization consumer authorization
     * @param workspace selected workspace
     * @param id task identifier
     * @param revision optionally pinned report revision
     * @return non-cacheable attachment
     */
    @GetMapping("/{id}/report")
    public ResponseEntity<byte[]> report(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace,
        @PathVariable("id") String id,
        @RequestParam(value = "revision", required = false) Integer revision) {
        var user = accounts.authenticate(authorization, workspace).user();
        byte[] report = service().report(user, id, revision);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(REPORT)
            .header(HttpHeaders.CONTENT_DISPOSITION, ATTACHMENT)
            .header("X-Content-Type-Options", "nosniff").body(report);
    }

    /**
     * Returns a sanitized consumer error.
     *
     * @param error public failure
     * @return non-cacheable error response
     */
    @ExceptionHandler(AssistantApiException.class)
    public ResponseEntity<ApiError> handleApiError(AssistantApiException error) {
        return response(error.status(), new ApiError(error.code(), error.getMessage()));
    }

    /**
     * Rejects malformed JSON and query parameters.
     *
     * @return sanitized validation failure
     */
    @ExceptionHandler({HttpMessageNotReadableException.class,
        MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiError> handleInvalid() {
        return handleApiError(invalid());
    }

    /**
     * Conceals storage failure details.
     *
     * @return sanitized temporary failure
     */
    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    public ResponseEntity<ApiError> handleStorageFailure() {
        return response(HttpStatus.SERVICE_UNAVAILABLE,
            new ApiError("INTERNAL_ERROR", "Local research is temporarily unavailable."));
    }

    private AssistantResearch service() {
        return research.orElseThrow(() -> new AssistantApiException(HttpStatus.FORBIDDEN,
            "CAPABILITY_UNAVAILABLE", "Local research is unavailable."));
    }

    private static Plan plan(JsonNode body, int fields) {
        requireObject(body, fields);
        JsonNode ids = body.path("sourceIds");
        if (!body.path("question").isTextual() || !ids.isArray() || ids.size() > 6) {
            throw invalid();
        }
        List<String> sources = new ArrayList<>();
        for (JsonNode id : ids) {
            if (!id.isTextual()) {
                throw invalid();
            }
            sources.add(id.textValue());
        }
        return new Plan(body.get("question").textValue(), List.copyOf(sources));
    }

    private static int revision(JsonNode body, String field) {
        JsonNode value = body.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw invalid();
        }
        return value.intValue();
    }

    private static void requireObject(JsonNode body, int fields) {
        if (body == null || !body.isObject() || body.size() != fields) {
            throw invalid();
        }
    }

    private static AssistantApiException invalid() {
        return new AssistantApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
            "Invalid local research request.");
    }

    private static <T> ResponseEntity<T> response(HttpStatus status, T body) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
            .contentType(MediaType.APPLICATION_JSON).body(body);
    }

    private record Plan(String question, List<String> sources) {

        @Override
        public String toString() {
            return "Plan[protected]";
        }
    }
}

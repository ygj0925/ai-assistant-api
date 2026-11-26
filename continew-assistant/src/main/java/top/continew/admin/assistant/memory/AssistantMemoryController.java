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

package top.continew.admin.assistant.memory;

import cn.dev33.satoken.annotation.SaIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import com.feiniaojin.gracefulresponse.api.ExcludeFromGracefulResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.TransactionException;
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
import top.continew.admin.assistant.account.AssistantAccountController.ApiError;
import top.continew.admin.assistant.account.AssistantAccounts;
import top.continew.admin.assistant.account.AssistantApiException;
import top.continew.admin.assistant.memory.AssistantMemories.Entry;
import top.continew.admin.assistant.memory.AssistantMemories.EntryPage;
import top.continew.admin.assistant.memory.AssistantMemories.Settings;
import top.continew.admin.assistant.memory.AssistantMemories.SettingsPatch;
import top.continew.starter.extension.tenant.annotation.TenantIgnore;
import top.continew.starter.log.annotation.Log;

/**
 * Non-cacheable manual memory and preference endpoints.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
@RestController
@RequestMapping({"/api/assistant/v1/memory", "/api/assistant/v1/projects/{projectId}/memory"})
@ConditionalOnProperty(name = "assistant.enabled", havingValue = "true")
@ExcludeFromGracefulResponse
@SaIgnore
@TenantIgnore
@Log(ignore = true)
public class AssistantMemoryController {

    private static final String WORKSPACE_HEADER = "X-Workspace-Id";
    private final AssistantAccounts accounts;
    private final AssistantMemories memory;

    /**
     * Wires consumer authentication and scoped memory controls.
     *
     * @param accounts consumer authentication
     * @param memory manual memory service
     */
    public AssistantMemoryController(AssistantAccounts accounts, AssistantMemories memory) {
        this.accounts = accounts;
        this.memory = memory;
    }

    /**
     * Reads effective settings without creating records.
     *
     * @param authorization consumer bearer token
     * @param workspace selected workspace
     * @param projectId optional owned project
     * @return effective settings
     */
    @GetMapping("/settings")
    public ResponseEntity<Settings> settings(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE_HEADER, required = false) String workspace,
        @PathVariable(name = "projectId", required = false) String projectId) {
        var owner = accounts.authenticate(authorization, workspace).user();
        return response(HttpStatus.OK, memory.settings(owner, projectId));
    }

    /**
     * Updates only the controls explicitly supplied by the authenticated owner.
     *
     * @param authorization consumer bearer token
     * @param workspace selected workspace
     * @param projectId optional owned project
     * @param body supplied settings patch
     * @return effective settings after updating
     */
    @PatchMapping("/settings")
    public ResponseEntity<Settings> update(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE_HEADER, required = false) String workspace,
        @PathVariable(name = "projectId", required = false) String projectId,
        @RequestBody(required = false) JsonNode body) {
        var owner = accounts.authenticate(authorization, workspace).user();
        if (body == null || !body.isObject() || body.isEmpty()) {
            throw invalidRequest();
        }
        JsonNode enabled = body.get("enabled");
        JsonNode history = body.get("referenceHistory");
        JsonNode instructions = body.get("instructions");
        int knownFields = (enabled == null ? 0 : 1) + (history == null ? 0 : 1)
            + (instructions == null ? 0 : 1);
        if (knownFields != body.size() || (enabled != null && !enabled.isBoolean())
            || (history != null && !history.isBoolean())
            || (instructions != null && !instructions.isTextual())) {
            throw invalidRequest();
        }
        return response(HttpStatus.OK, memory.update(owner, projectId,
            new SettingsPatch(enabled == null ? null : enabled.booleanValue(),
                history == null ? null : history.booleanValue(),
                instructions == null ? null : instructions.textValue())));
    }

    /**
     * Browses entries in the current scope.
     *
     * @param authorization consumer bearer token
     * @param workspace selected workspace
     * @param projectId optional owned project
     * @param limit optional page size
     * @param cursor optional continuation
     * @return accessible entries
     */
    @GetMapping("/entries")
    public ResponseEntity<EntryPage> list(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE_HEADER, required = false) String workspace,
        @PathVariable(name = "projectId", required = false) String projectId,
        @RequestParam(value = "limit", required = false) Integer limit,
        @RequestParam(value = "cursor", required = false) String cursor) {
        var owner = accounts.authenticate(authorization, workspace).user();
        return response(HttpStatus.OK, memory.list(owner, projectId, limit, cursor));
    }

    /**
     * Stores text explicitly supplied by the authenticated owner.
     *
     * @param authorization consumer bearer token
     * @param workspace selected workspace
     * @param projectId optional owned project
     * @param body JSON object with the memory text
     * @return stored entry
     */
    @PostMapping("/entries")
    public ResponseEntity<Entry> create(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE_HEADER, required = false) String workspace,
        @PathVariable(name = "projectId", required = false) String projectId,
        @RequestBody(required = false) JsonNode body) {
        var owner = accounts.authenticate(authorization, workspace).user();
        return response(HttpStatus.CREATED, memory.create(owner, projectId, content(body)));
    }

    /**
     * Reads one owned entry.
     *
     * @param authorization consumer bearer token
     * @param workspace selected workspace
     * @param projectId optional owned project
     * @param entryId memory identifier
     * @return active entry
     */
    @GetMapping("/entries/{entryId}")
    public ResponseEntity<Entry> get(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE_HEADER, required = false) String workspace,
        @PathVariable(name = "projectId", required = false) String projectId,
        @PathVariable("entryId") String entryId) {
        var owner = accounts.authenticate(authorization, workspace).user();
        return response(HttpStatus.OK, memory.get(owner, projectId, entryId));
    }

    /**
     * Replaces text for an owned entry and durably retires its old version.
     *
     * @param authorization consumer bearer token
     * @param workspace selected workspace
     * @param projectId optional owned project
     * @param entryId original memory identifier
     * @param body JSON object with the replacement memory text
     * @return current entry after the request
     */
    @PatchMapping("/entries/{entryId}")
    public ResponseEntity<Entry> replace(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE_HEADER, required = false) String workspace,
        @PathVariable(name = "projectId", required = false) String projectId,
        @PathVariable("entryId") String entryId,
        @RequestBody(required = false) JsonNode body) {
        var owner = accounts.authenticate(authorization, workspace).user();
        return response(HttpStatus.OK, memory.replace(owner, projectId, entryId, content(body)));
    }

    /**
     * Durably deletes one owned entry.
     *
     * @param authorization consumer bearer token
     * @param workspace selected workspace
     * @param projectId optional owned project
     * @param entryId memory identifier
     * @return empty confirmation
     */
    @DeleteMapping("/entries/{entryId}")
    public ResponseEntity<Void> delete(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE_HEADER, required = false) String workspace,
        @PathVariable(name = "projectId", required = false) String projectId,
        @PathVariable("entryId") String entryId) {
        var owner = accounts.authenticate(authorization, workspace).user();
        memory.delete(owner, projectId, entryId);
        return response(HttpStatus.NO_CONTENT, null);
    }

    /**
     * Returns a sanitized consumer failure without caching.
     *
     * @param failure expected failure
     * @return error envelope
     */
    @ExceptionHandler(AssistantApiException.class)
    public ResponseEntity<ApiError> handleApiError(AssistantApiException failure) {
        return response(failure.status(), new ApiError(failure.code(), failure.getMessage()));
    }

    /**
     * Rejects invalid JSON and query parameter types.
     *
     * @return sanitized validation error
     */
    @ExceptionHandler({HttpMessageNotReadableException.class,
        MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiError> handleInvalidRequest() {
        return handleApiError(invalidRequest());
    }

    /**
     * Conceals any storage failure details.
     *
     * @return temporary availability failure
     */
    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    public ResponseEntity<ApiError> handleStorageFailure() {
        return response(HttpStatus.SERVICE_UNAVAILABLE,
            new ApiError("INTERNAL_ERROR", "Memory is temporarily unavailable."));
    }

    private static String content(JsonNode body) {
        if (body == null || !body.isObject() || body.size() != 1
            || !body.path("content").isTextual()) {
            throw invalidRequest();
        }
        return body.get("content").textValue();
    }

    private static AssistantApiException invalidRequest() {
        return new AssistantApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
            "Invalid manual memory request.");
    }

    private static <T> ResponseEntity<T> response(HttpStatus status, T body) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(body);
    }
}

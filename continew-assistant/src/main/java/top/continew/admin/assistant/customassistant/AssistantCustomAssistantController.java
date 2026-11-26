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

package top.continew.admin.assistant.customassistant;

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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import top.continew.admin.assistant.account.AssistantAccountController.ApiError;
import top.continew.admin.assistant.account.AssistantAccounts;
import top.continew.admin.assistant.account.AssistantApiException;
import top.continew.admin.assistant.customassistant.AssistantCustomAssistants.Assistant;
import top.continew.admin.assistant.customassistant.AssistantCustomAssistants.Draft;
import top.continew.admin.assistant.customassistant.AssistantCustomAssistants.Page;
import top.continew.admin.assistant.customassistant.AssistantCustomAssistants.Summary;
import top.continew.admin.assistant.customassistant.AssistantCustomAssistants.Version;
import top.continew.starter.extension.tenant.annotation.TenantIgnore;
import top.continew.starter.log.annotation.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * Authenticated private assistant definitions and immutable version history.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
@RestController
@RequestMapping("/api/assistant/v1/custom-assistants")
@ConditionalOnProperty(name = "assistant.enabled", havingValue = "true")
@ExcludeFromGracefulResponse
@SaIgnore
@TenantIgnore
@Log(ignore = true)
public class AssistantCustomAssistantController {

    private static final String WORKSPACE = "X-Workspace-Id";

    private final AssistantAccounts accounts;
    private final AssistantCustomAssistants assistants;

    /**
     * Connects the owner registry to consumer account and workspace authorization.
     *
     * @param accounts consumer authorization
     * @param assistants private registry
     */
    public AssistantCustomAssistantController(AssistantAccounts accounts,
        AssistantCustomAssistants assistants) {
        this.accounts = accounts;
        this.assistants = assistants;
    }

    /**
     * Creates a private assistant and its first immutable version.
     *
     * @param authorization consumer bearer token
     * @param workspace selected workspace
     * @param key stable request UUID
     * @param body owner-authored version and optional fixed project
     * @return current assistant
     */
    @PostMapping
    public ResponseEntity<Assistant> create(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace,
        @RequestHeader(value = "Idempotency-Key", required = false) String key,
        @RequestBody(required = false) JsonNode body) {
        var owner = accounts.authenticate(authorization, workspace).user();
        requireObject(body);
        String project = body.has("projectId") ? field(body, "projectId") : null;
        return response(HttpStatus.CREATED, assistants.create(owner, key, project,
            draft(body, project == null ? 2 : 3)));
    }

    /**
     * Lists the owner's assistants in this workspace.
     *
     * @param authorization consumer bearer token
     * @param workspace selected workspace
     * @return private catalog
     */
    @GetMapping
    public ResponseEntity<Page<Summary>> list(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace) {
        return response(HttpStatus.OK,
            assistants.list(accounts.authenticate(authorization, workspace).user()));
    }

    /**
     * Reads an owned assistant.
     *
     * @param authorization consumer bearer token
     * @param workspace selected workspace
     * @param id assistant identifier
     * @return current owner definition
     */
    @GetMapping("/{assistantId}")
    public ResponseEntity<Assistant> get(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace,
        @PathVariable("assistantId") String id) {
        return response(HttpStatus.OK,
            assistants.get(accounts.authenticate(authorization, workspace).user(), id));
    }

    /**
     * Reads retained definitions without fetching their knowledge bodies.
     *
     * @param authorization consumer bearer token
     * @param workspace selected workspace
     * @param id owned assistant
     * @return bounded private history
     */
    @GetMapping("/{assistantId}/versions")
    public ResponseEntity<Page<Version>> versions(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace,
        @PathVariable("assistantId") String id) {
        return response(HttpStatus.OK,
            assistants.versions(accounts.authenticate(authorization, workspace).user(), id));
    }

    /**
     * Reads one owner-authored historical definition.
     *
     * @param authorization consumer bearer token
     * @param workspace selected workspace
     * @param id owned assistant
     * @param versionId owned version
     * @return immutable definition
     */
    @GetMapping("/{assistantId}/versions/{versionId}")
    public ResponseEntity<Version> version(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace,
        @PathVariable("assistantId") String id,
        @PathVariable("versionId") String versionId) {
        return response(HttpStatus.OK,
            assistants.version(accounts.authenticate(authorization, workspace).user(), id,
                versionId));
    }

    /**
     * Appends a version only if the observed head is still current.
     *
     * @param authorization consumer bearer token
     * @param workspace selected workspace
     * @param id owned assistant
     * @param body expected head and new owner definition
     * @return active assistant metadata with new head
     */
    @PostMapping("/{assistantId}/versions")
    public ResponseEntity<Assistant> revise(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace,
        @PathVariable("assistantId") String id,
        @RequestBody(required = false) JsonNode body) {
        var owner = accounts.authenticate(authorization, workspace).user();
        requireObject(body);
        return response(HttpStatus.CREATED,
            assistants.revise(owner, id, field(body, "baseVersionId"), draft(body, 3)));
    }

    /**
     * Activates a revalidated copy of an immutable historical version.
     *
     * @param authorization consumer bearer token
     * @param workspace selected workspace
     * @param id owned assistant
     * @param body expected head and historical version identifier
     * @return newly active rollback head
     */
    @PostMapping("/{assistantId}/rollback")
    public ResponseEntity<Assistant> rollback(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace,
        @PathVariable("assistantId") String id,
        @RequestBody(required = false) JsonNode body) {
        var owner = accounts.authenticate(authorization, workspace).user();
        requireObject(body);
        if (body.size() != 2) {
            throw invalid();
        }
        return response(HttpStatus.CREATED,
            assistants.rollback(owner, id, field(body, "baseVersionId"), field(body, "versionId")));
    }

    /**
     * Erases the private assistant and its versions.
     *
     * @param authorization consumer bearer token
     * @param workspace selected workspace
     * @param id owned assistant
     * @return empty response
     */
    @DeleteMapping("/{assistantId}")
    public ResponseEntity<Void> delete(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace,
        @PathVariable("assistantId") String id) {
        assistants.delete(accounts.authenticate(authorization, workspace).user(), id);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    /**
     * Returns an expected API failure without logging private configuration.
     *
     * @param failure public API failure
     * @return sanitized error envelope
     */
    @ExceptionHandler(AssistantApiException.class)
    public ResponseEntity<ApiError> handle(AssistantApiException failure) {
        return response(failure.status(), new ApiError(failure.code(), failure.getMessage()));
    }

    /**
     * Rejects unreadable request JSON.
     *
     * @return sanitized validation failure
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> unreadable() {
        return handle(invalid());
    }

    /**
     * Conceals storage details on recoverable failures.
     *
     * @return temporary capability error
     */
    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    public ResponseEntity<ApiError> unavailable() {
        return response(HttpStatus.SERVICE_UNAVAILABLE,
            new ApiError("CAPABILITY_UNAVAILABLE",
                "Private assistants are temporarily unavailable."));
    }

    private static Draft draft(JsonNode input, int requiredSize) {
        int fields = requiredSize + (input.has("resourceIds") ? 1 : 0)
            + (input.has("tools") ? 1 : 0);
        if (input.size() != fields) {
            throw invalid();
        }
        return new Draft(field(input, "name"), field(input, "instructions"),
            ids(input.get("resourceIds"), 8), ids(input.get("tools"), 1));
    }

    private static List<String> ids(JsonNode values, int limit) {
        if (values == null) {
            return List.of();
        }
        if (!values.isArray() || values.size() > limit) {
            throw invalid();
        }
        var result = new ArrayList<String>();
        for (JsonNode value : values) {
            if (!value.isTextual()) {
                throw invalid();
            }
            result.add(value.textValue());
        }
        return List.copyOf(result);
    }

    private static String field(JsonNode object, String name) {
        JsonNode value = object.path(name);
        if (!value.isTextual()) {
            throw invalid();
        }
        return value.textValue();
    }

    private static void requireObject(JsonNode value) {
        if (value == null || !value.isObject()) {
            throw invalid();
        }
    }

    private static AssistantApiException invalid() {
        return new AssistantApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
            "Invalid private assistant request.");
    }

    private static <T> ResponseEntity<T> response(HttpStatus status, T body) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(body);
    }
}

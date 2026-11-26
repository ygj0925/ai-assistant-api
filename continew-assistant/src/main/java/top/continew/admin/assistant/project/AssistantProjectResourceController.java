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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import top.continew.admin.assistant.account.AssistantAccountController.ApiError;
import top.continew.admin.assistant.account.AssistantAccounts;
import top.continew.admin.assistant.account.AssistantApiException;
import top.continew.admin.assistant.project.AssistantProjectResources.Resource;
import top.continew.admin.assistant.project.AssistantProjectResources.ResourcePage;
import top.continew.starter.extension.tenant.annotation.TenantIgnore;
import top.continew.starter.log.annotation.Log;

/**
 * Authenticated, non-cacheable access to text materials in private projects.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
@RestController
@RequestMapping("/api/assistant/v1/projects/{projectId}/resources")
@ConditionalOnProperty(name = "assistant.enabled", havingValue = "true")
@ExcludeFromGracefulResponse
@SaIgnore
@TenantIgnore
@Log(ignore = true)
public class AssistantProjectResourceController {

    private static final String WORKSPACE_HEADER = "X-Workspace-Id";

    private final AssistantAccounts accounts;
    private final AssistantProjectResources resources;

    /**
     * Wires scoped consumer authentication and private materials.
     *
     * @param accounts consumer authentication
     * @param resources project text materials
     */
    public AssistantProjectResourceController(AssistantAccounts accounts,
        AssistantProjectResources resources) {
        this.accounts = accounts;
        this.resources = resources;
    }

    /**
     * Stores one bounded text material.
     *
     * @param authorization consumer bearer token
     * @param workspace selected workspace
     * @param projectId owned project
     * @param body JSON string fields for display name and plain text
     * @return created material
     */
    @PostMapping
    public ResponseEntity<Resource> create(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE_HEADER, required = false) String workspace,
        @PathVariable("projectId") String projectId,
        @RequestBody(required = false) JsonNode body) {
        var owner = accounts.authenticate(authorization, workspace).user();
        if (body == null || !body.isObject() || body.size() != 2 || !body.path("name").isTextual()
            || !body.path("content").isTextual()) {
            throw new AssistantApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                "Material name and content must be text fields.");
        }
        return response(HttpStatus.CREATED, resources.create(owner, projectId,
            body.get("name").textValue(), body.get("content").textValue()));
    }

    /**
     * Browses metadata or searches names and contents.
     *
     * @param authorization consumer bearer token
     * @param workspace selected workspace
     * @param projectId owned project
     * @param query optional text to match
     * @param limit optional page size
     * @param cursor optional continuation
     * @return visible metadata page
     */
    @GetMapping
    public ResponseEntity<ResourcePage> list(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE_HEADER, required = false) String workspace,
        @PathVariable("projectId") String projectId,
        @RequestParam(value = "q", required = false) String query,
        @RequestParam(value = "limit", required = false) Integer limit,
        @RequestParam(value = "cursor", required = false) String cursor) {
        var owner = accounts.authenticate(authorization, workspace).user();
        return response(HttpStatus.OK, resources.list(owner, projectId, query, limit, cursor));
    }

    /**
     * Loads original plain text for one owned material.
     *
     * @param authorization consumer bearer token
     * @param workspace selected workspace
     * @param projectId owned project
     * @param resourceId material ID
     * @return authorized text material
     */
    @GetMapping("/{resourceId}")
    public ResponseEntity<Resource> get(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE_HEADER, required = false) String workspace,
        @PathVariable("projectId") String projectId,
        @PathVariable("resourceId") String resourceId) {
        var owner = accounts.authenticate(authorization, workspace).user();
        return response(HttpStatus.OK, resources.get(owner, projectId, resourceId));
    }

    /**
     * Removes an owned material from future reads and searches.
     *
     * @param authorization consumer bearer token
     * @param workspace selected workspace
     * @param projectId owned project
     * @param resourceId material ID
     * @return an empty success response
     */
    @DeleteMapping("/{resourceId}")
    public ResponseEntity<Void> delete(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE_HEADER, required = false) String workspace,
        @PathVariable("projectId") String projectId,
        @PathVariable("resourceId") String resourceId) {
        var owner = accounts.authenticate(authorization, workspace).user();
        resources.delete(owner, projectId, resourceId);
        return response(HttpStatus.NO_CONTENT, null);
    }

    /**
     * Returns a sanitized consumer failure without caching.
     *
     * @param failure expected public failure
     * @return error envelope
     */
    @ExceptionHandler(AssistantApiException.class)
    public ResponseEntity<ApiError> handleApiError(AssistantApiException failure) {
        return response(failure.status(), new ApiError(failure.code(), failure.getMessage()));
    }

    /**
     * Rejects malformed JSON and query parameters without displaying their contents.
     *
     * @return validation failure
     */
    @ExceptionHandler({HttpMessageNotReadableException.class,
        MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiError> handleInvalidRequest() {
        return response(HttpStatus.BAD_REQUEST,
            new ApiError("VALIDATION_ERROR", "Invalid project material request."));
    }

    /**
     * Conceals storage details.
     *
     * @return temporary availability failure
     */
    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    public ResponseEntity<ApiError> handleStorageFailure() {
        return response(HttpStatus.SERVICE_UNAVAILABLE,
            new ApiError("INTERNAL_ERROR", "Project materials are temporarily unavailable."));
    }

    private static <T> ResponseEntity<T> response(HttpStatus status, T body) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(body);
    }
}

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
import com.feiniaojin.gracefulresponse.api.ExcludeFromGracefulResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
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
import top.continew.admin.assistant.project.AssistantProjects.Project;
import top.continew.admin.assistant.project.AssistantProjects.ProjectPage;
import top.continew.admin.assistant.project.AssistantProjects.ProjectWrite;
import top.continew.starter.extension.tenant.annotation.TenantIgnore;
import top.continew.starter.log.annotation.Log;

/**
 * Authenticated consumer endpoints for private projects.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
@RestController
@RequestMapping("/api/assistant/v1/projects")
@ConditionalOnProperty(name = "assistant.enabled", havingValue = "true")
@ExcludeFromGracefulResponse
@SaIgnore
@TenantIgnore
@Log(ignore = true)
public class AssistantProjectController {

    private static final String WORKSPACE_HEADER = "X-Workspace-Id";
    private final AssistantAccounts accounts;
    private final AssistantProjects projects;

    /**
     * Creates the consumer project controller.
     *
     * @param accounts consumer authentication
     * @param projects private project catalog
     */
    public AssistantProjectController(AssistantAccounts accounts, AssistantProjects projects) {
        this.accounts = accounts;
        this.projects = projects;
    }

    /**
     * Lists the authenticated consumer's private projects.
     *
     * @param authorization consumer bearer token
     * @param workspace selected workspace
     * @param limit optional project page size
     * @param cursor optional continuation
     * @return project page
     */
    @GetMapping
    public ResponseEntity<ProjectPage> list(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE_HEADER, required = false) String workspace,
        @RequestParam(value = "limit", required = false) Integer limit,
        @RequestParam(value = "cursor", required = false) String cursor) {
        var owner = accounts.authenticate(authorization, workspace).user();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(projects.list(owner, limit, cursor));
    }

    /**
     * Creates a private project.
     *
     * @param authorization consumer bearer token
     * @param workspace selected workspace
     * @param input name and optional description
     * @return created project
     */
    @PostMapping
    public ResponseEntity<Project> create(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE_HEADER, required = false) String workspace,
        @RequestBody ProjectWrite input) {
        var owner = accounts.authenticate(authorization, workspace).user();
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
            .body(projects.create(owner, input));
    }

    /**
     * Loads one private project.
     *
     * @param authorization consumer bearer token
     * @param workspace selected workspace
     * @param id project identifier
     * @return private project metadata
     */
    @GetMapping("/{projectId}")
    public ResponseEntity<Project> get(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE_HEADER, required = false) String workspace,
        @PathVariable("projectId") String id) {
        var owner = accounts.authenticate(authorization, workspace).user();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(projects.get(owner, id));
    }

    /**
     * Updates one private project's metadata.
     *
     * @param authorization consumer bearer token
     * @param workspace selected workspace
     * @param id project identifier
     * @param input name or description changes
     * @return current project metadata
     */
    @PatchMapping("/{projectId}")
    public ResponseEntity<Project> update(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE_HEADER, required = false) String workspace,
        @PathVariable("projectId") String id, @RequestBody ProjectWrite input) {
        var owner = accounts.authenticate(authorization, workspace).user();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(projects.update(owner, id, input));
    }

    /**
     * Returns the standard sanitized consumer error.
     *
     * @param failure consumer-visible request failure
     * @return error envelope
     */
    @ExceptionHandler(AssistantApiException.class)
    public ResponseEntity<ApiError> handleApiError(AssistantApiException failure) {
        return ResponseEntity.status(failure.status()).cacheControl(CacheControl.noStore())
            .body(new ApiError(failure.code(), failure.getMessage()));
    }

    /**
     * Maps malformed project requests to the public validation code.
     *
     * @return validation failure
     */
    @ExceptionHandler({HttpMessageNotReadableException.class,
        MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiError> handleInvalidRequest() {
        return handleApiError(new AssistantApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
            "Invalid project request."));
    }

    /**
     * Hides database details in storage failures.
     *
     * @return sanitized catalog failure
     */
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ApiError> handleStorageError() {
        return handleApiError(
            new AssistantApiException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "The project catalog is unavailable."));
    }
}

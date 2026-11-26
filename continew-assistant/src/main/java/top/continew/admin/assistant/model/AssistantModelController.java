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

package top.continew.admin.assistant.model;

import cn.dev33.satoken.annotation.SaIgnore;
import com.feiniaojin.gracefulresponse.api.ExcludeFromGracefulResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import top.continew.admin.assistant.account.AssistantAccountController.ApiError;
import top.continew.admin.assistant.account.AssistantAccounts;
import top.continew.admin.assistant.account.AssistantApiException;
import top.continew.admin.assistant.organization.AssistantOrganizationPolicies;
import top.continew.starter.extension.tenant.annotation.TenantIgnore;
import top.continew.starter.log.annotation.Log;

import java.util.List;
import java.util.Map;

/**
 * Lists text models available to an authenticated assistant consumer.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
@RestController
@RequestMapping("/api/assistant/v1/models")
@ConditionalOnProperty(name = "assistant.enabled", havingValue = "true")
@ExcludeFromGracefulResponse
@SaIgnore
@TenantIgnore
@Log(ignore = true)
public class AssistantModelController {

    private final AssistantAccounts accounts;
    private final AssistantModels models;
    private final AssistantOrganizationPolicies policies;

    /**
     * Creates the model catalog endpoint.
     *
     * @param accounts consumer authentication
     * @param models safe model metadata
     * @param policies current organization model visibility controls
     */
    public AssistantModelController(AssistantAccounts accounts, AssistantModels models,
        AssistantOrganizationPolicies policies) {
        this.accounts = accounts;
        this.models = models;
        this.policies = policies;
    }

    /**
     * Returns enabled text models.
     *
     * @param authorization consumer bearer token
     * @param workspace requested workspace
     * @return the model catalog
     */
    @GetMapping
    public ResponseEntity<Map<String, List<AssistantModels.Model>>> list(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = "X-Workspace-Id", required = false) String workspace) {
        var principal = accounts.authenticate(authorization, workspace);
        var policy = policies.effective(principal.user().workspaceId(), false);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(Map.of("models",
                models.list().stream().filter(model -> policy.allowsModel(model.id()))
                    .toList()));
    }

    /**
     * Returns the standard public error response.
     *
     * @param error consumer failure
     * @return a sanitized error response
     */
    @ExceptionHandler(AssistantApiException.class)
    public ResponseEntity<ApiError> handle(AssistantApiException error) {
        return ResponseEntity.status(error.status()).cacheControl(CacheControl.noStore())
            .body(new ApiError(error.code(), error.getMessage()));
    }

    /**
     * Handles storage errors without sharing database details.
     *
     * @return catalog service error
     */
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ApiError> handleStorageError() {
        return handle(new AssistantApiException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
            "The model catalog is unavailable."));
    }
}

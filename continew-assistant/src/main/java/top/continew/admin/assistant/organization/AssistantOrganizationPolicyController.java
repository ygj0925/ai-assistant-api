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

package top.continew.admin.assistant.organization;

import cn.dev33.satoken.annotation.SaIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import com.feiniaojin.gracefulresponse.api.ExcludeFromGracefulResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import top.continew.admin.assistant.account.AssistantAccountController.ApiError;
import top.continew.admin.assistant.account.AssistantAccounts;
import top.continew.admin.assistant.account.AssistantApiException;
import top.continew.admin.assistant.organization.AssistantOrganizationPolicies.View;
import top.continew.starter.extension.tenant.annotation.TenantIgnore;
import top.continew.starter.log.annotation.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * Exposes owner-reviewed policy writes for one current organization.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
@RestController
@RequestMapping("/api/assistant/v1/organizations/current/policy")
@ConditionalOnProperty(name = "assistant.enabled", havingValue = "true")
@ExcludeFromGracefulResponse
@SaIgnore
@TenantIgnore
@Log(ignore = true)
public class AssistantOrganizationPolicyController {

    private static final String WORKSPACE = "X-Workspace-Id";
    private final AssistantAccounts accounts;
    private final AssistantOrganizationPolicies policies;

    /**
     * Connects the actual consumer workspace to its reviewed organization policy.
     *
     * @param accounts scoped consumer authentication
     * @param policies exact policy authoring and enforcement
     */
    public AssistantOrganizationPolicyController(AssistantAccounts accounts,
        AssistantOrganizationPolicies policies) {
        this.accounts = accounts;
        this.policies = policies;
    }

    /**
     * Returns the current policy and review revision to its owner.
     *
     * @param authorization consumer bearer header
     * @param workspace selected organization workspace
     * @return safe policy view
     */
    @GetMapping
    public ResponseEntity<View> read(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace) {
        var result = policies.read(accounts.authenticate(authorization, workspace));
        accounts.authenticate(authorization, workspace);
        return respond(HttpStatus.OK, result);
    }

    /**
     * Replaces a reviewed exact policy using the current owner's confirmed intent.
     *
     * @param authorization consumer bearer header
     * @param workspace selected organization workspace
     * @param body exact revision, allowlist, simulator decision and consent
     * @return newly effective policy
     */
    @PutMapping
    public ResponseEntity<View> update(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace,
        @RequestBody(required = false) JsonNode body) {
        var principal = accounts.authenticate(authorization, workspace);
        if (body == null || !body.isObject() || body.size() != 4
            || !body.path("baseRevision").isTextual() || !body.path("allowedModelIds").isArray()
            || body.path("allowedModelIds").size() > 16
            || !body.path("sandboxConnectorAllowed").isBoolean()
            || !body.path("confirm").isBoolean()) {
            throw invalid();
        }
        List<String> models = new ArrayList<>();
        for (JsonNode model : body.path("allowedModelIds")) {
            if (!model.isTextual()) {
                throw invalid();
            }
            models.add(model.textValue());
        }
        return respond(HttpStatus.OK,
            policies.update(principal, body.path("baseRevision").textValue(),
                models, body.path("sandboxConnectorAllowed").booleanValue(),
                body.path("confirm").booleanValue()));
    }

    /**
     * Maps an authorized typed failure without cache or private fields.
     *
     * @param failure bounded service failure
     * @return typed error envelope
     */
    @ExceptionHandler(AssistantApiException.class)
    public ResponseEntity<ApiError> handle(AssistantApiException failure) {
        return respond(failure.status(), new ApiError(failure.code(), failure.getMessage()));
    }

    /**
     * Maps malformed bodies to the stable request error.
     *
     * @return typed validation error
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> malformed() {
        return handle(invalid());
    }

    /**
     * Conceals data-source details if policy storage is unavailable.
     *
     * @return temporary capability error
     */
    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    public ResponseEntity<ApiError> storage() {
        return handle(
            new AssistantApiException(HttpStatus.SERVICE_UNAVAILABLE, "CAPABILITY_UNAVAILABLE",
                "Organization policy management is temporarily unavailable."));
    }

    private static AssistantApiException invalid() {
        return new AssistantApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
            "Review and confirm the exact organization policy and model identifiers.");
    }

    private static <T> ResponseEntity<T> respond(HttpStatus status, T body) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
            .cacheControl(CacheControl.noStore()).body(body);
    }
}

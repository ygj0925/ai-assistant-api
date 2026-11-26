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

package top.continew.admin.eligibility;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.sign.template.SaSignTemplate;
import cn.dev33.satoken.stp.StpUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.feiniaojin.gracefulresponse.api.ExcludeFromGracefulResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import top.continew.admin.assistant.account.AssistantAccountController.ApiError;
import top.continew.admin.assistant.account.AssistantApiException;
import top.continew.admin.assistant.eligibility.AssistantEligibility;
import top.continew.admin.assistant.eligibility.AssistantEligibility.Consent;
import top.continew.admin.assistant.eligibility.AssistantEligibility.Operator;
import top.continew.admin.common.context.UserContextHolder;
import top.continew.starter.log.annotation.Log;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Provisions explicitly synthetic relationships through independently authorized administration.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
@RestController
@RequestMapping("/system/assistant/eligibility/workspaces/{workspaceId}/enrollments")
@ConditionalOnProperty(name = "assistant.enabled", havingValue = "true")
@ExcludeFromGracefulResponse
@Log(ignore = true)
public class AssistantEligibilityProvisionController {

    private static final String PROVISION_PERMISSION = "assistant:eligibility:provision";
    private final AssistantEligibility eligibility;

    /**
     * Creates the synthetic provisioning controller.
     *
     * @param eligibility explicit permission fixture decisions
     */
    public AssistantEligibilityProvisionController(AssistantEligibility eligibility) {
        this.eligibility = eligibility;
    }

    /**
     * Enrolls two distinct active test identities under the exact assigned workspace.
     *
     * @param request actual ContiNew platform request
     * @param workspaceId exact designated synthetic workspace
     * @param body bounded enrollment fields and explicit confirmation
     * @return opaque consent state
     */
    @PostMapping
    public ResponseEntity<Consent> enroll(HttpServletRequest request,
        @PathVariable("workspaceId") String workspaceId,
        @RequestBody(required = false) JsonNode body) {
        Operator actor = operator(request);
        if (body == null || !body.isObject() || body.size() != 4
            || !body.path("participantAccountId").isTextual()
            || !body.path("guardianAccountId").isTextual() || !body.path("scenario").isTextual()
            || !body.path("confirm").isBoolean()) {
            throw invalid();
        }
        return respond(HttpStatus.CREATED, eligibility.enroll(actor, workspaceId,
            body.path("participantAccountId").textValue(),
            body.path("guardianAccountId").textValue(),
            body.path("scenario").textValue(), body.path("confirm").booleanValue()));
    }

    /**
     * Emits a typed non-cacheable policy or authentication error.
     *
     * @param failure controlled consumer-independent policy failure
     * @return stable administration error
     */
    @ExceptionHandler(AssistantApiException.class)
    public ResponseEntity<ApiError> typed(AssistantApiException failure) {
        return respond(failure.status(), new ApiError(failure.code(), failure.getMessage()));
    }

    /**
     * Maps absent platform authentication.
     *
     * @return controlled unauthorized response
     */
    @ExceptionHandler(NotLoginException.class)
    public ResponseEntity<ApiError> loginRequired() {
        return typed(unauthorized());
    }

    /**
     * Maps missing platform authorization.
     *
     * @return controlled forbidden response
     */
    @ExceptionHandler(NotPermissionException.class)
    public ResponseEntity<ApiError> permissionRequired() {
        return typed(forbidden());
    }

    /**
     * Maps malformed JSON to a bounded validation response.
     *
     * @return stable validation response
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> malformed() {
        return typed(invalid());
    }

    /**
     * Conceals internal persistence details.
     *
     * @return bounded storage response
     */
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ApiError> storage() {
        return typed(new AssistantApiException(HttpStatus.SERVICE_UNAVAILABLE,
            "CAPABILITY_UNAVAILABLE", "Synthetic enrollment is temporarily unavailable."));
    }

    private static Operator operator(HttpServletRequest request) {
        if (request.getParameterMap().containsKey(SaSignTemplate.sign)) {
            throw unauthorized();
        }
        StpUtil.checkLogin();
        StpUtil.checkPermission(PROVISION_PERMISSION);
        Long tenant = UserContextHolder.getExtraContext(StpUtil.getTokenValue()).getTenantId();
        if (tenant == null) {
            throw forbidden();
        }
        return new Operator(StpUtil.getLoginIdAsLong(), tenant);
    }

    private static AssistantApiException unauthorized() {
        return new AssistantApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED",
            "An administrative login is required.");
    }

    private static AssistantApiException forbidden() {
        return new AssistantApiException(HttpStatus.FORBIDDEN, "FORBIDDEN",
            "Synthetic enrollment is unavailable for this scope.");
    }

    private static AssistantApiException invalid() {
        return new AssistantApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
            "Confirm an exact synthetic enrollment in the assigned workspace.");
    }

    private static <T> ResponseEntity<T> respond(HttpStatus status, T value) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
            .cacheControl(CacheControl.noStore()).body(value);
    }
}

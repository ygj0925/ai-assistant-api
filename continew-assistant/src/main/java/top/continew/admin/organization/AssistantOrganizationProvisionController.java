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

package top.continew.admin.organization;

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
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import top.continew.admin.assistant.account.AssistantAccountController.ApiError;
import top.continew.admin.assistant.account.AssistantApiException;
import top.continew.admin.assistant.organization.AssistantOrganizations;
import top.continew.admin.assistant.organization.AssistantOrganizations.Operator;
import top.continew.admin.assistant.organization.AssistantOrganizations.Summary;
import top.continew.admin.common.context.UserContextHolder;
import top.continew.starter.log.annotation.Log;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Provisions an organization under the caller's interactive platform tenant.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
@RestController
@RequestMapping("/system/assistant/organizations")
@ConditionalOnProperty(name = "assistant.enabled", havingValue = "true")
@ExcludeFromGracefulResponse
@Log(ignore = true)
public class AssistantOrganizationProvisionController {

    private static final String PROVISION_PERMISSION = "assistant:organization:provision";
    private final AssistantOrganizations organizations;

    /**
     * Connects the platform role check to organization provisioning.
     *
     * @param organizations organization scope and local seat allocation
     */
    public AssistantOrganizationProvisionController(AssistantOrganizations organizations) {
        this.organizations = organizations;
    }

    /**
     * Provisions one exact workspace for a named existing owner.
     *
     * @param key canonical idempotency key
     * @param body explicit owner name and seat limit confirmation
     * @param request actual interactive administration request
     * @return provisioned workspace summary
     */
    @PostMapping
    public ResponseEntity<Summary> provision(
        @RequestHeader(value = "Idempotency-Key", required = false) String key,
        @RequestBody(required = false) JsonNode body, HttpServletRequest request) {
        var actor = operator(request);
        if (body == null || !body.isObject() || body.size() != 4
            || !body.path("ownerAccountId").isTextual() || !body.path("name").isTextual()
            || !body.path("seatLimit").isIntegralNumber()
            || !body.path("seatLimit").canConvertToInt()
            || !body.path("confirm").isBoolean()) {
            throw invalid();
        }
        Summary result =
            organizations.provision(actor, key, body.path("ownerAccountId").textValue(),
                body.path("name").textValue(), body.path("seatLimit").intValue(),
                body.path("confirm").booleanValue());
        return response(HttpStatus.CREATED, result);
    }

    private static Operator operator(HttpServletRequest request) {
        if (request.getParameterMap().containsKey(SaSignTemplate.sign)) {
            throw unauthenticated();
        }
        StpUtil.checkLogin();
        StpUtil.checkPermission(PROVISION_PERMISSION);
        Long tenantId = UserContextHolder.getExtraContext(StpUtil.getTokenValue()).getTenantId();
        if (tenantId == null) {
            throw forbidden();
        }
        return new Operator(StpUtil.getLoginIdAsLong(), tenantId);
    }

    /**
     * Returns a bounded platform error.
     *
     * @param failure safe service error
     * @return non-cacheable error envelope
     */
    @ExceptionHandler(AssistantApiException.class)
    public ResponseEntity<ApiError> handle(AssistantApiException failure) {
        return response(failure.status(), new ApiError(failure.code(), failure.getMessage()));
    }

    /**
     * Handles missing platform sessions separately from consumer authentication.
     *
     * @return unauthorized response
     */
    @ExceptionHandler(NotLoginException.class)
    public ResponseEntity<ApiError> loginRequired() {
        return handle(unauthenticated());
    }

    /**
     * Handles denied platform roles.
     *
     * @return forbidden response
     */
    @ExceptionHandler(NotPermissionException.class)
    public ResponseEntity<ApiError> permissionRequired() {
        return handle(forbidden());
    }

    /**
     * Handles malformed provisioning data.
     *
     * @return validation error
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> malformed() {
        return handle(invalid());
    }

    /**
     * Conceals data-storage details.
     *
     * @return temporary service error
     */
    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    public ResponseEntity<ApiError> storage() {
        return handle(
            new AssistantApiException(HttpStatus.SERVICE_UNAVAILABLE, "CAPABILITY_UNAVAILABLE",
                "Organization provisioning is temporarily unavailable."));
    }

    private static <T> ResponseEntity<T> response(HttpStatus status, T body) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
            .cacheControl(CacheControl.noStore()).body(body);
    }

    private static AssistantApiException unauthenticated() {
        return new AssistantApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED",
            "Sign in through the administration console.");
    }

    private static AssistantApiException forbidden() {
        return new AssistantApiException(HttpStatus.FORBIDDEN, "FORBIDDEN",
            "The current administration tenant cannot provision organizations.");
    }

    private static AssistantApiException invalid() {
        return new AssistantApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
            "The organization provision request is invalid.");
    }
}

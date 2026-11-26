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

package top.continew.admin.moderation;

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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import top.continew.admin.assistant.account.AssistantAccountController.ApiError;
import top.continew.admin.assistant.account.AssistantApiException;
import top.continew.admin.assistant.catalog.AssistantCatalog;
import top.continew.admin.assistant.catalog.AssistantCatalog.CatalogRelease;
import top.continew.admin.assistant.catalog.AssistantCatalog.Operator;
import top.continew.admin.assistant.catalog.AssistantCatalog.Page;
import top.continew.admin.assistant.catalog.AssistantCatalog.Review;
import top.continew.admin.common.context.UserContextHolder;
import top.continew.starter.log.annotation.Log;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Reviews catalogue releases through exact ContiNew permissions and workspace assignments.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
@RestController
@RequestMapping("/system/assistant/catalog/workspaces/{workspaceId}/releases")
@ConditionalOnProperty(name = "assistant.enabled", havingValue = "true")
@ExcludeFromGracefulResponse
@Log(ignore = true)
public class AssistantCatalogReviewController {

    private static final String REVIEW_PERMISSION = "assistant:catalog:review";
    private static final String PUBLISH_PERMISSION = "assistant:catalog:publish";
    private final AssistantCatalog catalog;

    /**
     * Wires the independently authorized catalogue review endpoints.
     *
     * @param catalog release service
     */
    public AssistantCatalogReviewController(AssistantCatalog catalog) {
        this.catalog = catalog;
    }

    /**
     * Lists releases from the operator's assigned consumer workspace.
     *
     * @param request actual ContiNew session request
     * @param workspaceId explicitly selected workspace
     * @param limit bounded page size
     * @param cursor continuation token
     * @return scoped release statuses
     */
    @GetMapping
    public ResponseEntity<Page<CatalogRelease>> releases(HttpServletRequest request,
        @PathVariable("workspaceId") String workspaceId,
        @RequestParam(value = "limit", required = false) Integer limit,
        @RequestParam(value = "cursor", required = false) String cursor) {
        return respond(HttpStatus.OK,
            catalog.review(operator(request, REVIEW_PERMISSION), workspaceId, limit, cursor));
    }

    /**
     * Shows the exact submitted disclosure and operational evidence for one scoped release.
     *
     * @param request actual ContiNew session request
     * @param workspaceId explicitly selected workspace
     * @param releaseId chosen exact release
     * @return review dossier and reports
     */
    @GetMapping("/{releaseId}")
    public ResponseEntity<Review> dossier(HttpServletRequest request,
        @PathVariable("workspaceId") String workspaceId,
        @PathVariable("releaseId") String releaseId) {
        return respond(HttpStatus.OK,
            catalog.dossier(operator(request, REVIEW_PERMISSION), workspaceId, releaseId));
    }

    /**
     * Approves, rejects, or immediately withdraws one exact release.
     *
     * @param request actual ContiNew session request
     * @param workspaceId explicitly selected workspace
     * @param releaseId chosen exact release
     * @param body affirmative decision
     * @return scoped release receipt
     */
    @PostMapping("/{releaseId}/decision")
    public ResponseEntity<CatalogRelease> decision(HttpServletRequest request,
        @PathVariable("workspaceId") String workspaceId,
        @PathVariable("releaseId") String releaseId,
        @RequestBody(required = false) JsonNode body) {
        Operator actor = operator(request, PUBLISH_PERMISSION);
        if (body == null || !body.isObject() || body.size() != 2
            || !body.path("decision").isTextual() || !body.path("confirm").isBoolean()) {
            throw invalid();
        }
        return respond(HttpStatus.OK, catalog.decide(actor, workspaceId, releaseId,
            body.path("decision").textValue(), body.path("confirm").booleanValue()));
    }

    /**
     * Returns a bounded error for catalogue operations.
     *
     * @param failure controlled review failure
     * @return sanitized error and cache policy
     */
    @ExceptionHandler(AssistantApiException.class)
    public ResponseEntity<ApiError> typed(AssistantApiException failure) {
        return respond(failure.status(), new ApiError(failure.code(), failure.getMessage()));
    }

    /**
     * Rejects requests without the administrative token audience.
     *
     * @return typed authentication error
     */
    @ExceptionHandler(NotLoginException.class)
    public ResponseEntity<ApiError> loginRequired() {
        return typed(unauthorized());
    }

    /**
     * Rejects administrators without the exact review permission.
     *
     * @return typed authorization error
     */
    @ExceptionHandler(NotPermissionException.class)
    public ResponseEntity<ApiError> permissionRequired() {
        return typed(forbidden());
    }

    /**
     * Rejects invalid review parameters and bodies.
     *
     * @return typed validation error
     */
    @ExceptionHandler({HttpMessageNotReadableException.class,
        MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiError> malformed() {
        return typed(invalid());
    }

    /**
     * Conceals persistence details.
     *
     * @return bounded service-unavailable error
     */
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ApiError> storage() {
        return typed(new AssistantApiException(HttpStatus.SERVICE_UNAVAILABLE,
            "CAPABILITY_UNAVAILABLE", "Catalogue review is temporarily unavailable."));
    }

    private static Operator operator(HttpServletRequest request, String permission) {
        if (request.getParameterMap().containsKey(SaSignTemplate.sign)) {
            throw unauthorized();
        }
        StpUtil.checkLogin();
        StpUtil.checkPermission(permission);
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
            "Catalogue review is unavailable for this scope.");
    }

    private static AssistantApiException invalid() {
        return new AssistantApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
            "Confirm a valid exact catalogue decision.");
    }

    private static <T> ResponseEntity<T> respond(HttpStatus status, T body) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
            .cacheControl(CacheControl.noStore()).body(body);
    }
}

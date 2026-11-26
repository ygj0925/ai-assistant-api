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
import com.feiniaojin.gracefulresponse.api.ExcludeFromGracefulResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
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
import top.continew.admin.assistant.moderation.AssistantReports;
import top.continew.admin.assistant.moderation.AssistantReports.Decision;
import top.continew.admin.assistant.moderation.AssistantReports.Detail;
import top.continew.admin.assistant.moderation.AssistantReports.Operator;
import top.continew.admin.assistant.moderation.AssistantReports.Receipt;
import top.continew.admin.common.context.UserContextHolder;
import top.continew.starter.log.annotation.Log;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;

/**
 * Reviews reports through the ContiNew administration session and workspace grant.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
@RestController
@RequestMapping("/system/assistant/moderation/workspaces/{workspaceId}/reports")
@ConditionalOnProperty(name = "assistant.enabled", havingValue = "true")
@ExcludeFromGracefulResponse
@Log(ignore = true)
public class AssistantModerationController {

    private static final String REVIEW_PERMISSION = "assistant:report:review";
    private static final String DECIDE_PERMISSION = "assistant:report:decide";
    private static final String WITHDRAW_PERMISSION = "assistant:report:withdraw";
    private final AssistantReports reports;

    /**
     * Constructs the administration endpoint.
     *
     * @param reports workspace-scoped report store
     */
    public AssistantModerationController(AssistantReports reports) {
        this.reports = reports;
    }

    /**
     * Lists unresolved reports in one authorized workspace.
     *
     * @param workspaceId workspace selector validated by product grants
     * @param limit maximum case count
     * @param request incoming administrative request
     * @return pending cases
     */
    @GetMapping
    public ResponseEntity<Map<String, List<Receipt>>> list(
        @PathVariable("workspaceId") String workspaceId,
        @RequestParam(value = "limit", defaultValue = "25") int limit,
        HttpServletRequest request) {
        var pending = reports.pending(operator(request, REVIEW_PERMISSION), workspaceId, limit);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(Map.of("items", pending));
    }

    /**
     * Reads a specific case and its evidence.
     *
     * @param workspaceId authorized workspace selector
     * @param reportId case ID
     * @param request incoming administrative request
     * @return review detail
     */
    @GetMapping("/{reportId}")
    public ResponseEntity<Detail> detail(@PathVariable("workspaceId") String workspaceId,
        @PathVariable("reportId") String reportId, HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            reports.detail(operator(request, REVIEW_PERMISSION), workspaceId, reportId));
    }

    /**
     * Records a decision and its operator audit.
     *
     * @param workspaceId authorized workspace selector
     * @param reportId case ID
     * @param decision requested decision
     * @param request incoming administrative request
     * @return case receipt after the decision
     */
    @PostMapping("/{reportId}/decision")
    public ResponseEntity<Receipt> decide(@PathVariable("workspaceId") String workspaceId,
        @PathVariable("reportId") String reportId,
        @RequestBody(required = false) Decision decision, HttpServletRequest request) {
        Operator actor = operator(request, DECIDE_PERMISSION);
        if (decision != null && "withdraw".equals(decision.decision())) {
            StpUtil.checkPermission(WITHDRAW_PERMISSION);
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            reports.decide(actor, workspaceId, reportId, decision));
    }

    private static Operator operator(HttpServletRequest request, String permission) {
        // The app-signature path is separate from interactive administration sessions.
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

    /**
     * Returns a bounded administrative error.
     *
     * @param error public moderation failure
     * @return sanitized error
     */
    @ExceptionHandler(AssistantApiException.class)
    public ResponseEntity<ApiError> handle(AssistantApiException error) {
        return ResponseEntity.status(error.status()).cacheControl(CacheControl.noStore())
            .body(new ApiError(error.code(), error.getMessage()));
    }

    /**
     * Handles requests without a live administration login.
     *
     * @return unauthorized error
     */
    @ExceptionHandler(NotLoginException.class)
    public ResponseEntity<ApiError> loginRequired() {
        return handle(unauthorized());
    }

    /**
     * Handles administration RBAC rejection.
     *
     * @return forbidden error
     */
    @ExceptionHandler(NotPermissionException.class)
    public ResponseEntity<ApiError> permissionRequired() {
        return handle(forbidden());
    }

    /**
     * Handles malformed administrative requests.
     *
     * @return validation error
     */
    @ExceptionHandler({HttpMessageNotReadableException.class,
        MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiError> malformed() {
        return handle(new AssistantApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
            "The review request is invalid."));
    }

    /**
     * Handles moderation store failures.
     *
     * @return sanitized service error
     */
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ApiError> storage() {
        return handle(new AssistantApiException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
            "Moderation is temporarily unavailable."));
    }

    private static AssistantApiException unauthorized() {
        return new AssistantApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED",
            "An administration session is required.");
    }

    private static AssistantApiException forbidden() {
        return new AssistantApiException(HttpStatus.FORBIDDEN, "FORBIDDEN",
            "The moderation permission is required.");
    }
}

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

package top.continew.admin.assistant.privacy;

import cn.dev33.satoken.annotation.SaIgnore;
import com.feiniaojin.gracefulresponse.api.ExcludeFromGracefulResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import top.continew.admin.assistant.account.AssistantAccountController.ApiError;
import top.continew.admin.assistant.account.AssistantAccounts;
import top.continew.admin.assistant.account.AssistantApiException;
import top.continew.admin.assistant.privacy.AssistantErasure.EraseAccount;
import top.continew.starter.extension.tenant.annotation.TenantIgnore;
import top.continew.starter.log.annotation.Log;
import top.continew.starter.ratelimiter.annotation.RateLimiter;
import top.continew.starter.ratelimiter.exception.RateLimiterException;

import jakarta.servlet.http.HttpServletRequest;
import java.util.concurrent.TimeUnit;

/**
 * Downloads user-owned assistant data through the consumer session boundary.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
@RestController
@RequestMapping("/api/assistant/v1/privacy")
@ConditionalOnProperty(name = "assistant.enabled", havingValue = "true")
@ExcludeFromGracefulResponse
@SaIgnore
@TenantIgnore
@Log(ignore = true)
public class AssistantPrivacyController {

    private static final String ATTACHMENT =
        ContentDisposition.attachment().filename("assistant-data.json")
            .build().toString();

    private final AssistantAccounts accounts;
    private final AssistantExport exports;
    private final AssistantErasure erasure;

    /**
     * Constructs the consumer privacy endpoint.
     *
     * @param accounts authentication and workspace membership access
     * @param exports export implementation
     * @param erasure journaled privacy erasure service
     */
    public AssistantPrivacyController(AssistantAccounts accounts, AssistantExport exports,
        AssistantErasure erasure) {
        this.accounts = accounts;
        this.exports = exports;
        this.erasure = erasure;
    }

    /**
     * Downloads a complete JSON export page.
     *
     * @param authorization consumer session header
     * @param workspace selected consumer workspace
     * @param cursor export continuation
     * @return download attachment
     */
    @GetMapping(value = "/export", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> export(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = "X-Workspace-Id", required = false) String workspace,
        @RequestParam(value = "cursor", required = false) String cursor) {
        var principal = accounts.authenticate(authorization, workspace);
        byte[] bytes = exports.download(principal, cursor);
        accounts.authenticate(authorization, workspace);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .header(HttpHeaders.CONTENT_DISPOSITION, ATTACHMENT)
            .header("X-Content-Type-Options", "nosniff")
            .contentLength(bytes.length).contentType(MediaType.APPLICATION_JSON).body(bytes);
    }

    /**
     * Accepts an explicitly confirmed whole-account deletion after current-password verification.
     *
     * @param authorization active consumer authorization header
     * @param request current password and deletion intent
     * @param servletRequest trusted transport peer for brute-force throttling
     * @return deletion completion
     */
    @PostMapping("/account-erasure")
    @RateLimiter(name = "assistant.account-erasure", key = "#root.args[2].remoteAddr", rate = 6,
        interval = 1, unit = TimeUnit.MINUTES)
    public ResponseEntity<Void> eraseAccount(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestBody(required = false) EraseAccount request, HttpServletRequest servletRequest) {
        erasure.deleteAccount(accounts.authenticate(authorization, null), request);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    /**
     * Returns the normal consumer error envelope for a failed download request.
     *
     * @param error consumer API failure
     * @return sanitized error
     */
    @ExceptionHandler(AssistantApiException.class)
    public ResponseEntity<ApiError> handle(AssistantApiException error) {
        return ResponseEntity.status(error.status()).cacheControl(CacheControl.noStore())
            .body(new ApiError(error.code(), error.getMessage()));
    }

    /**
     * Handles database errors without exposing records or SQL.
     *
     * @return sanitized service error
     */
    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    public ResponseEntity<ApiError> storage() {
        return handle(new AssistantApiException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
            "The privacy operation is temporarily unavailable."));
    }

    /**
     * Returns a sanitized error for invalid deletion requests.
     *
     * @return validation failure
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> malformed() {
        return handle(new AssistantApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
            "A valid privacy request body is required."));
    }

    /**
     * Applies the public quota error format to current-password checks.
     *
     * @return retryable rate-limit response
     */
    @ExceptionHandler(RateLimiterException.class)
    public ResponseEntity<ApiError> throttled() {
        return handle(new AssistantApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED",
            "Try again later."));
    }
}

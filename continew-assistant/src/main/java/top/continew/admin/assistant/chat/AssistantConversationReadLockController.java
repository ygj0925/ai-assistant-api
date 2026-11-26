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

package top.continew.admin.assistant.chat;

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
import top.continew.admin.assistant.chat.AssistantConversationReadLock.LockedPage;
import top.continew.starter.extension.tenant.annotation.TenantIgnore;
import top.continew.starter.log.annotation.Log;
import top.continew.starter.ratelimiter.annotation.RateLimiter;
import top.continew.starter.ratelimiter.exception.RateLimiterException;

import jakarta.servlet.http.HttpServletRequest;
import java.util.concurrent.TimeUnit;

/**
 * Exposes owner-only conversation protection and current-password recovery.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
@RestController
@RequestMapping("/api/assistant/v1/conversations")
@ConditionalOnProperty(name = "assistant.enabled", havingValue = "true")
@ExcludeFromGracefulResponse
@SaIgnore
@TenantIgnore
@Log(ignore = true)
public class AssistantConversationReadLockController {

    private static final String WORKSPACE = "X-Workspace-Id";

    private final AssistantAccounts accounts;
    private final AssistantConversationReadLock locks;

    /**
     * Wires current consumer scope and recoverable read locks.
     *
     * @param accounts current consumer authentication
     * @param locks read policy transitions and locked ID catalogue
     */
    public AssistantConversationReadLockController(AssistantAccounts accounts,
        AssistantConversationReadLock locks) {
        this.accounts = accounts;
        this.locks = locks;
    }

    /**
     * Lists only IDs of conversations whose content remains locked.
     *
     * @param authorization consumer credentials
     * @param workspace selected workspace
     * @param cursor prior opaque ID
     * @param limit bounded page size
     * @return scoped identity page
     */
    @GetMapping("/read-locked")
    public ResponseEntity<LockedPage> catalogue(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace,
        @RequestParam(value = "cursor", required = false) String cursor,
        @RequestParam(value = "limit", defaultValue = "25") int limit) {
        var principal = accounts.authenticate(authorization, workspace);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(locks.catalogue(principal.user(), cursor, limit));
    }

    /**
     * Protects a conversation after affirmative consent from its owner.
     *
     * @param authorization consumer credentials
     * @param workspace selected workspace
     * @param id targeted conversation
     * @param input affirmative confirmation
     * @return non-cacheable committed success
     */
    @PostMapping("/{id}/read-lock")
    public ResponseEntity<Void> lock(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace,
        @PathVariable("id") String id, @RequestBody(required = false) JsonNode input) {
        var principal = accounts.authenticate(authorization, workspace);
        JsonNode confirm = field(input, "confirm");
        if (!confirm.isBoolean()) {
            throw invalid();
        }
        locks.lock(principal, id, confirm.booleanValue());
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    /**
     * Recovers private reads only after confirming the current account password.
     *
     * @param authorization consumer credentials
     * @param workspace selected workspace
     * @param id targeted conversation
     * @param input current credential
     * @param request current network request used for the attempt limit
     * @return non-cacheable committed success
     */
    @PostMapping("/{id}/read-unlock")
    @RateLimiter(name = "assistant.conversation-read-unlock", key = "#root.args[4].remoteAddr",
        rate = 8, interval = 1, unit = TimeUnit.MINUTES)
    public ResponseEntity<Void> unlock(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace,
        @PathVariable("id") String id, @RequestBody(required = false) JsonNode input,
        HttpServletRequest request) {
        var principal = accounts.authenticate(authorization, workspace);
        JsonNode password = field(input, "currentPassword");
        if (!password.isTextual()) {
            throw invalid();
        }
        locks.unlock(principal, id, password.textValue());
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    /**
     * Emits the safe status without caching or rendering submitted data.
     *
     * @param error safe consumer failure
     * @return typed non-cacheable error
     */
    @ExceptionHandler(AssistantApiException.class)
    public ResponseEntity<ApiError> handle(AssistantApiException error) {
        return failure(error.status(), new ApiError(error.code(), error.getMessage()));
    }

    /**
     * Rejects malformed request fields.
     *
     * @return non-cacheable validation failure
     */
    @ExceptionHandler({HttpMessageNotReadableException.class,
        MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiError> malformed() {
        return handle(invalid());
    }

    /**
     * Rejects excess password attempts without revealing conversation state.
     *
     * @return non-cacheable rate limit
     */
    @ExceptionHandler(RateLimiterException.class)
    public ResponseEntity<ApiError> rateLimited() {
        return failure(HttpStatus.TOO_MANY_REQUESTS,
            new ApiError("RATE_LIMITED", "Try again later."));
    }

    /**
     * Conceals low-level database and transaction details.
     *
     * @return non-cacheable temporary failure
     */
    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    public ResponseEntity<ApiError> unavailable() {
        return failure(HttpStatus.SERVICE_UNAVAILABLE,
            new ApiError("INTERNAL_ERROR", "Conversation locking is temporarily unavailable."));
    }

    private static JsonNode field(JsonNode input, String name) {
        if (input == null || !input.isObject() || input.size() != 1) {
            throw invalid();
        }
        return input.path(name);
    }

    private static AssistantApiException invalid() {
        return new AssistantApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
            "Invalid conversation lock request.");
    }

    private static ResponseEntity<ApiError> failure(HttpStatus status, ApiError error) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
            .cacheControl(CacheControl.noStore()).body(error);
    }
}

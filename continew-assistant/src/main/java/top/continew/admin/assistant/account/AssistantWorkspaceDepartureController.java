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

package top.continew.admin.assistant.account;

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
import org.springframework.web.bind.annotation.RestController;
import top.continew.admin.assistant.account.AssistantAccountController.ApiError;
import top.continew.admin.assistant.account.AssistantWorkspaceDeparture.Preview;
import top.continew.starter.extension.tenant.annotation.TenantIgnore;
import top.continew.starter.log.annotation.Log;

/**
 * Exposes exact self-departure for current non-personal workspace memberships.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
@RestController
@RequestMapping("/api/assistant/v1/auth/workspaces/{workspaceId}")
@ConditionalOnProperty(name = "assistant.enabled", havingValue = "true")
@ExcludeFromGracefulResponse
@SaIgnore
@TenantIgnore
@Log(ignore = true)
public class AssistantWorkspaceDepartureController {

    private final AssistantAccounts accounts;
    private final AssistantWorkspaceDeparture service;

    /**
     * Wires personal session authentication to exact departure scope.
     *
     * @param accounts current signed-in consumer session
     * @param service membership departure
     */
    public AssistantWorkspaceDepartureController(AssistantAccounts accounts,
        AssistantWorkspaceDeparture service) {
        this.accounts = accounts;
        this.service = service;
    }

    /**
     * Prepares the currently enrolled generation for the user's confirmation.
     *
     * @param authorization consumer bearer header
     * @param workspaceId exact target workspace
     * @return current non-cacheable departure preview
     */
    @GetMapping("/leave-preview")
    public ResponseEntity<Preview> preview(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @PathVariable("workspaceId") String workspaceId) {
        var principal = accounts.authenticate(authorization, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(service.preview(principal, workspaceId));
    }

    /**
     * Confirms departure of the exact enrolled membership after reviewing its preview.
     *
     * @param authorization consumer bearer header
     * @param workspaceId exact target workspace
     * @param input reviewed generation and explicit affirmative consent
     * @return empty committed success response
     */
    @PostMapping("/leave")
    public ResponseEntity<Void> leave(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @PathVariable("workspaceId") String workspaceId,
        @RequestBody(required = false) JsonNode input) {
        var principal = accounts.authenticate(authorization, null);
        if (input == null || !input.isObject() || input.size() != 2
            || !input.path("membershipRevision").isTextual()
            || !input.path("confirm").isBoolean()) {
            throw invalid();
        }
        service.leave(principal, workspaceId, input.path("membershipRevision").textValue(),
            input.path("confirm").booleanValue());
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    /**
     * Preserves the operation's typed rejection without cache or storage details.
     *
     * @param error safe public failure
     * @return typed non-cacheable rejection
     */
    @ExceptionHandler(AssistantApiException.class)
    public ResponseEntity<ApiError> handle(AssistantApiException error) {
        return failure(error.status(), new ApiError(error.code(), error.getMessage()));
    }

    /**
     * Rejects malformed request bodies.
     *
     * @return typed validation rejection
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> malformed() {
        return handle(invalid());
    }

    /**
     * Conceals persistence or transaction details.
     *
     * @return typed non-cacheable temporary failure
     */
    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    public ResponseEntity<ApiError> unavailable() {
        return failure(HttpStatus.SERVICE_UNAVAILABLE,
            new ApiError("INTERNAL_ERROR", "Workspace departure is temporarily unavailable."));
    }

    private static AssistantApiException invalid() {
        return new AssistantApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
            "Invalid departure confirmation.");
    }

    private static ResponseEntity<ApiError> failure(HttpStatus status, ApiError error) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
            .cacheControl(CacheControl.noStore()).body(error);
    }
}

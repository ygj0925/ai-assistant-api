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

package top.continew.admin.assistant.message;

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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import top.continew.admin.assistant.account.AssistantAccountController.ApiError;
import top.continew.admin.assistant.account.AssistantAccounts;
import top.continew.admin.assistant.account.AssistantApiException;
import top.continew.admin.assistant.memory.AssistantProjectMemorySources;
import top.continew.admin.assistant.run.AssistantHistoryReferences;
import top.continew.starter.extension.tenant.annotation.TenantIgnore;
import top.continew.starter.log.annotation.Log;

/**
 * Consumer conversation message history endpoint.
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
public class AssistantMessageController {

    private final AssistantAccounts accounts;
    private final AssistantMessageHistory history;
    private final AssistantHistoryReferences historyReferences;
    private final AssistantProjectMemorySources memorySources;

    /**
     * Creates the message history controller.
     *
     * @param accounts consumer authentication
     * @param history message history service
     * @param historyReferences authorized prior reply receipts
     * @param memorySources authorized project memory source receipts
     */
    public AssistantMessageController(AssistantAccounts accounts, AssistantMessageHistory history,
        AssistantHistoryReferences historyReferences, AssistantProjectMemorySources memorySources) {
        this.accounts = accounts;
        this.history = history;
        this.historyReferences = historyReferences;
        this.memorySources = memorySources;
    }

    /**
     * Reads message history from an authorized conversation.
     *
     * @param authorization consumer bearer token
     * @param workspace requested workspace
     * @param id conversation ID
     * @param cursor continuation cursor
     * @param limit page size
     * @return message page
     */
    @GetMapping("/{id}/messages")
    public ResponseEntity<AssistantMessageHistory.MessagePage> list(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = "X-Workspace-Id", required = false) String workspace,
        @PathVariable("id") String id,
        @RequestParam(value = "cursor", required = false) String cursor,
        @RequestParam(value = "limit", defaultValue = "25") int limit) {
        var principal = accounts.authenticate(authorization, workspace);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(history.list(principal, id, cursor, limit));
    }

    /**
     * Reads the current operation receipt for a selected source from a completed reply.
     *
     * @param authorization consumer bearer token
     * @param workspace requested workspace
     * @param id target conversation ID
     * @param replyId completed reply whose source was used
     * @return authorized current source state
     */
    @GetMapping("/{id}/messages/{replyId}/history-reference")
    public ResponseEntity<AssistantHistoryReferences.Receipt> historyReference(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = "X-Workspace-Id", required = false) String workspace,
        @PathVariable("id") String id,
        @PathVariable("replyId") String replyId) {
        var principal = accounts.authenticate(authorization, workspace);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(historyReferences.receipt(principal, id, replyId));
    }

    /**
     * Reads the current project memories used by a completed reply.
     *
     * @param authorization consumer bearer token
     * @param workspace requested workspace
     * @param id conversation ID
     * @param replyId completed reply whose project memory was used
     * @return authorized excerpts in source order
     */
    @GetMapping("/{id}/messages/{replyId}/project-memory-sources")
    public ResponseEntity<AssistantProjectMemorySources.Sources> projectMemorySources(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = "X-Workspace-Id", required = false) String workspace,
        @PathVariable("id") String id,
        @PathVariable("replyId") String replyId) {
        var principal = accounts.authenticate(authorization, workspace);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(memorySources.sources(principal, id, replyId));
    }

    /**
     * Returns a standard consumer error.
     *
     * @param error consumer failure
     * @return public error response
     */
    @ExceptionHandler(AssistantApiException.class)
    public ResponseEntity<ApiError> handle(AssistantApiException error) {
        return ResponseEntity.status(error.status()).cacheControl(CacheControl.noStore())
            .body(new ApiError(error.code(), error.getMessage()));
    }

    /**
     * Returns a validation error for a malformed page limit.
     *
     * @return validation error response
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleInvalidLimit() {
        return handle(new AssistantApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
            "Invalid message limit."));
    }

    /**
     * Handles storage failures without exposing database details.
     *
     * @return service error response
     */
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ApiError> handleStorageError() {
        return handle(new AssistantApiException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
            "Message history is unavailable."));
    }
}

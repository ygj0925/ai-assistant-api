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
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import top.continew.admin.assistant.account.AssistantAccountController.ApiError;
import top.continew.admin.assistant.account.AssistantAccounts;
import top.continew.admin.assistant.account.AssistantApiException;
import top.continew.admin.assistant.chat.AssistantConversations.Conversation;
import top.continew.admin.assistant.chat.AssistantConversations.ConversationCreate;
import top.continew.admin.assistant.chat.AssistantConversations.ConversationPage;
import top.continew.admin.assistant.chat.AssistantConversations.ConversationPatch;
import top.continew.admin.assistant.chat.AssistantConversations.ConversationQuery;
import top.continew.starter.extension.tenant.annotation.TenantIgnore;
import top.continew.starter.log.annotation.Log;

/**
 * Versioned consumer conversation endpoints.
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
public class AssistantConversationController {

    private static final String WORKSPACE_HEADER = "X-Workspace-Id";
    private final AssistantAccounts accounts;
    private final AssistantConversations conversations;

    /**
     * Wires the consumer authentication and catalog services.
     *
     * @param accounts consumer authentication
     * @param conversations conversation catalog
     */
    public AssistantConversationController(AssistantAccounts accounts,
        AssistantConversations conversations) {
        this.accounts = accounts;
        this.conversations = conversations;
    }

    /**
     * Lists one filtered page of visible conversations.
     *
     * @param authorization consumer bearer header
     * @param workspace requested workspace or null
     * @param q optional title query
     * @param archived optional archive filter
     * @param limit optional page limit
     * @param cursor optional continuation
     * @param projectId optional private project filter
     * @param unfiled optional filter for conversations outside projects
     * @return conversation page
     */
    @GetMapping
    public ResponseEntity<ConversationPage> list(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE_HEADER, required = false) String workspace,
        @RequestParam(value = "q", required = false) String q,
        @RequestParam(value = "archived", required = false) String archived,
        @RequestParam(value = "limit", required = false) Integer limit,
        @RequestParam(value = "cursor", required = false) String cursor,
        @RequestParam(value = "projectId", required = false) String projectId,
        @RequestParam(value = "unfiled", required = false) String unfiled) {
        var user = accounts.authenticate(authorization, workspace).user();
        return ok(
            conversations.list(user, new ConversationQuery(q, optionalBoolean(archived, "Archived"),
                limit, cursor, projectId, optionalBoolean(unfiled, "Unfiled"))));
    }

    /**
     * Creates a conversation owned by the active consumer.
     *
     * @param authorization consumer bearer header
     * @param workspace requested workspace or null
     * @param body conversation input
     * @return created conversation
     */
    @PostMapping
    public ResponseEntity<Conversation> create(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE_HEADER, required = false) String workspace,
        @RequestBody ConversationCreate body) {
        var user = accounts.authenticate(authorization, workspace).user();
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
            .body(conversations.create(user, body));
    }

    /**
     * Reads one conversation owned by the active consumer.
     *
     * @param authorization consumer bearer header
     * @param workspace requested workspace or null
     * @param id conversation identifier
     * @return conversation metadata
     */
    @GetMapping("/{conversationId}")
    public ResponseEntity<Conversation> get(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE_HEADER, required = false) String workspace,
        @PathVariable("conversationId") String id) {
        return ok(conversations.get(accounts.authenticate(authorization, workspace).user(), id));
    }

    /**
     * Updates metadata of a conversation owned by the active consumer.
     *
     * @param authorization consumer bearer header
     * @param workspace requested workspace or null
     * @param id conversation identifier
     * @param body metadata updates
     * @return updated conversation
     */
    @PatchMapping("/{conversationId}")
    public ResponseEntity<Conversation> update(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE_HEADER, required = false) String workspace,
        @PathVariable("conversationId") String id,
        @RequestBody ConversationPatch body) {
        return ok(
            conversations.update(accounts.authenticate(authorization, workspace).user(), id, body));
    }

    /**
     * Replaces the private project membership of a conversation.
     *
     * @param authorization consumer bearer header
     * @param workspace requested workspace or null
     * @param id conversation identifier
     * @param body explicit project identifier or null to remove the filing
     * @return updated conversation
     * @since 4.2.0
     */
    @PutMapping("/{conversationId}/project")
    public ResponseEntity<Conversation> move(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE_HEADER, required = false) String workspace,
        @PathVariable("conversationId") String id, @RequestBody JsonNode body) {
        var user = accounts.authenticate(authorization, workspace).user();
        JsonNode project = body == null ? null : body.get("projectId");
        if (project == null || !(project.isNull() || project.isTextual())) {
            throw new AssistantApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                "An explicit project identifier or null is required.");
        }
        return ok(conversations.move(user, id, project.textValue()));
    }

    /**
     * Replaces the sharing restriction for an owned conversation.
     *
     * @param authorization consumer bearer header
     * @param workspace requested workspace or null
     * @param id conversation identifier
     * @param body explicit restriction state
     * @return updated conversation
     */
    @PutMapping("/{conversationId}/sharing-restriction")
    public ResponseEntity<Conversation> setSharingRestriction(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE_HEADER, required = false) String workspace,
        @PathVariable("conversationId") String id, @RequestBody JsonNode body) {
        var user = accounts.authenticate(authorization, workspace).user();
        JsonNode restricted = body == null ? null : body.get("restricted");
        if (restricted == null || !restricted.isBoolean()) {
            throw new AssistantApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                "An explicit boolean sharing restriction is required.");
        }
        return ok(conversations.setSharingRestriction(user, id, restricted.booleanValue()));
    }

    /**
     * Removes access to a conversation owned by the active consumer.
     *
     * @param authorization consumer bearer header
     * @param workspace requested workspace or null
     * @param id conversation identifier
     * @return empty response
     */
    @DeleteMapping("/{conversationId}")
    public ResponseEntity<Void> delete(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE_HEADER, required = false) String workspace,
        @PathVariable("conversationId") String id) {
        conversations.delete(accounts.authenticate(authorization, workspace).user(), id);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    /**
     * Returns an API error without the administration response wrapper.
     *
     * @param exception consumer API error
     * @return HTTP error
     */
    @ExceptionHandler(AssistantApiException.class)
    public ResponseEntity<ApiError> handleApiError(AssistantApiException exception) {
        return error(exception.status(), exception.code(), exception.getMessage());
    }

    /**
     * Maps malformed JSON or query types to the consumer error code.
     *
     * @return HTTP validation error
     */
    @ExceptionHandler({HttpMessageNotReadableException.class,
        MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiError> handleInvalidRequest() {
        return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Invalid conversation request.");
    }

    /**
     * Maps storage errors without disclosing SQL details.
     *
     * @return HTTP service error
     */
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ApiError> handleStorageError() {
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
            "The catalog is unavailable.");
    }

    private static <T> ResponseEntity<T> ok(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    private static Boolean optionalBoolean(String value, String label) {
        if (value == null) {
            return null;
        }
        return switch (value) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new AssistantApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                label + " must be true or false.");
        };
    }

    private static ResponseEntity<ApiError> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
            .body(new ApiError(code, message));
    }
}

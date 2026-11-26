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

package top.continew.admin.assistant.share;

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
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.DeleteMapping;
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
import top.continew.admin.assistant.account.AssistantAccounts.Principal;
import top.continew.admin.assistant.account.AssistantApiException;
import top.continew.admin.assistant.chat.AssistantConversations.Conversation;
import top.continew.admin.assistant.share.AssistantShares.Page;
import top.continew.admin.assistant.share.AssistantShares.Preview;
import top.continew.admin.assistant.share.AssistantShares.Published;
import top.continew.admin.assistant.share.AssistantShares.Request;
import top.continew.admin.assistant.share.AssistantShares.View;
import top.continew.starter.extension.tenant.annotation.TenantIgnore;
import top.continew.starter.log.annotation.Log;

import java.util.Set;

/**
 * Exposes sharing management and token redemption without logging the request body.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
@RestController
@RequestMapping("/api/assistant/v1")
@ConditionalOnProperty(name = "assistant.enabled", havingValue = "true")
@ExcludeFromGracefulResponse
@SaIgnore
@TenantIgnore
@Log(ignore = true)
public class AssistantShareController {

    private static final String WORKSPACE = "X-Workspace-Id";
    private static final Set<String> PREVIEW_FIELDS = Set.of("sourceMessageId", "scope", "audience",
        "includesAttachments");
    private static final Set<String> PUBLISH_FIELDS = Set.of("sourceMessageId", "scope", "audience",
        "revision", "includesAttachments");
    private static final Set<String> TOKEN_FIELDS = Set.of("token");

    private final AssistantAccounts accounts;
    private final AssistantShares shares;

    /**
     * Connects the sharing endpoints to consumer authorization.
     *
     * @param accounts account sessions and workspace access
     * @param shares share previews, links and copies
     */
    public AssistantShareController(AssistantAccounts accounts, AssistantShares shares) {
        this.accounts = accounts;
        this.shares = shares;
    }

    /**
     * Previews the exact text and audience of an owned share.
     *
     * @param authorization consumer session
     * @param workspace selected workspace
     * @param conversationId owned source conversation
     * @param body selected message, scope and audience
     * @return preview to confirm before publication
     */
    @PostMapping("/conversations/{conversationId}/share-previews")
    public ResponseEntity<Preview> preview(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace,
        @PathVariable("conversationId") String conversationId,
        @RequestBody(required = false) JsonNode body) {
        Principal principal = accounts.authenticate(authorization, workspace);
        return response(HttpStatus.OK, shares.preview(principal, conversationId,
            request(body, PREVIEW_FIELDS)));
    }

    /**
     * Publishes a reviewed share.
     *
     * @param authorization consumer session
     * @param workspace selected workspace
     * @param conversationId owned source conversation
     * @param body share selection and reviewed revision
     * @return receipt with the newly issued link token
     */
    @PostMapping("/conversations/{conversationId}/shares")
    public ResponseEntity<Published> publish(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace,
        @PathVariable("conversationId") String conversationId,
        @RequestBody(required = false) JsonNode body) {
        Principal principal = accounts.authenticate(authorization, workspace);
        return response(HttpStatus.CREATED, shares.publish(principal, conversationId,
            request(body, PUBLISH_FIELDS)));
    }

    /**
     * Lists an owned conversation's share metadata.
     *
     * @param authorization consumer session
     * @param workspace selected workspace
     * @param conversationId owned source conversation
     * @param limit page size
     * @param cursor opaque continuation
     * @return metadata page
     */
    @GetMapping("/conversations/{conversationId}/shares")
    public ResponseEntity<Page> list(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace,
        @PathVariable("conversationId") String conversationId,
        @RequestParam(value = "limit", defaultValue = "25") int limit,
        @RequestParam(value = "cursor", required = false) String cursor) {
        Principal principal = accounts.authenticate(authorization, workspace);
        return response(HttpStatus.OK, shares.list(principal, conversationId, cursor, limit));
    }

    /**
     * Revokes an existing share owned by this consumer.
     *
     * @param authorization consumer session
     * @param workspace selected workspace
     * @param shareId published link identifier
     * @return empty receipt
     */
    @DeleteMapping("/shares/{shareId}")
    public ResponseEntity<Void> revoke(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace,
        @PathVariable("shareId") String shareId) {
        shares.revoke(accounts.authenticate(authorization, workspace), shareId);
        return response(HttpStatus.NO_CONTENT, null);
    }

    /**
     * Reads a token without requiring credentials for the public audience.
     *
     * @param authorization optional consumer session
     * @param workspace selected workspace for members
     * @param body token supplied outside the request URL
     * @return allowed snapshot
     */
    @PostMapping("/shares/read")
    public ResponseEntity<View> read(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace,
        @RequestBody(required = false) JsonNode body) {
        Principal viewer = authorization == null ? null : recipient(authorization, workspace);
        return response(HttpStatus.OK, shares.read(token(body), viewer));
    }

    /**
     * Copies a redeemable token into the recipient's active workspace.
     *
     * @param authorization consumer session
     * @param workspace selected destination workspace
     * @param body token supplied outside the request URL
     * @return recipient-owned conversation
     */
    @PostMapping("/shares/copy")
    public ResponseEntity<Conversation> copy(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace,
        @RequestBody(required = false) JsonNode body) {
        Principal viewer = recipient(authorization, workspace);
        return response(HttpStatus.CREATED, shares.copy(token(body), viewer));
    }

    /**
     * Sends a safe consumer error.
     *
     * @param error expected public failure
     * @return non-cacheable error response
     */
    @ExceptionHandler(AssistantApiException.class)
    public ResponseEntity<ApiError> knownError(AssistantApiException error) {
        return response(error.status(), new ApiError(error.code(), error.getMessage()));
    }

    /**
     * Rejects malformed requests without echoing the payload.
     *
     * @return safe validation failure
     */
    @ExceptionHandler({HttpMessageNotReadableException.class,
        MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiError> invalidRequest() {
        return knownError(AssistantShares.validation("Invalid share request."));
    }

    /**
     * Avoids reporting SQL details or uncommitted mutations.
     *
     * @return temporary service failure
     */
    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    public ResponseEntity<ApiError> unavailable() {
        return response(HttpStatus.SERVICE_UNAVAILABLE, new ApiError("SHARING_UNAVAILABLE",
            "Conversation sharing is temporarily unavailable."));
    }

    private Principal recipient(String authorization, String workspace) {
        try {
            return accounts.authenticate(authorization, workspace);
        } catch (AssistantApiException e) {
            if (e.status() == HttpStatus.FORBIDDEN) {
                throw AssistantShares.notFound();
            }
            throw e;
        }
    }

    private static Request request(JsonNode body, Set<String> fields) {
        if (!hasOnlyFields(body, fields)) {
            throw AssistantShares.validation("The share accepts only text selection and audience.");
        }
        JsonNode attachments = body.get("includesAttachments");
        if (attachments != null && (!attachments.isBoolean() || attachments.booleanValue())) {
            throw AssistantShares.validation("Attachments are unavailable in shares.");
        }
        return new Request(text(body, "sourceMessageId"), text(body, "scope"),
            text(body, "audience"),
            text(body, "revision"));
    }

    private static String token(JsonNode body) {
        if (!hasOnlyFields(body, TOKEN_FIELDS)) {
            throw AssistantShares.notFound();
        }
        return text(body, "token");
    }

    private static boolean hasOnlyFields(JsonNode body, Set<String> fields) {
        if (body == null || !body.isObject()) {
            return false;
        }
        var names = body.fieldNames();
        while (names.hasNext()) {
            if (!fields.contains(names.next())) {
                return false;
            }
        }
        return true;
    }

    private static String text(JsonNode body, String field) {
        JsonNode value = body.get(field);
        return value != null && value.isTextual() ? value.textValue() : null;
    }

    private static <T> ResponseEntity<T> response(HttpStatus status, T body) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
            .header("Referrer-Policy", "no-referrer").body(body);
    }
}

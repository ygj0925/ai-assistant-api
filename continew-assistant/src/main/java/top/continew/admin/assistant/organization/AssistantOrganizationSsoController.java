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

package top.continew.admin.assistant.organization;

import cn.dev33.satoken.annotation.SaIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.feiniaojin.gracefulresponse.api.ExcludeFromGracefulResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import top.continew.admin.assistant.account.AssistantAccountController.ApiError;
import top.continew.admin.assistant.account.AssistantAccounts;
import top.continew.admin.assistant.account.AssistantAccounts.Principal;
import top.continew.admin.assistant.account.AssistantApiException;
import top.continew.admin.assistant.chat.AssistantConversations;
import top.continew.admin.assistant.chat.AssistantConversations.Conversation;
import top.continew.admin.assistant.chat.AssistantConversations.ConversationPage;
import top.continew.admin.assistant.chat.AssistantConversations.ConversationQuery;
import top.continew.admin.assistant.organization.AssistantOrganizationSso.Completion;
import top.continew.admin.assistant.organization.AssistantOrganizationSso.LinkStatus;
import top.continew.admin.assistant.organization.AssistantOrganizationSso.Start;
import top.continew.admin.assistant.organization.AssistantOrganizations.Summary;
import top.continew.starter.extension.tenant.annotation.TenantIgnore;
import top.continew.starter.log.annotation.Log;
import top.continew.starter.ratelimiter.annotation.RateLimiter;
import top.continew.starter.ratelimiter.exception.RateLimiterException;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * Exposes a dedicated, read-only organization sign-in surface.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
@RestController
@RequestMapping("/api/assistant/v1/organization-sso")
@ConditionalOnProperty(name = "assistant.enabled", havingValue = "true")
@ExcludeFromGracefulResponse
@SaIgnore
@TenantIgnore
@Log(ignore = true)
class AssistantOrganizationSsoController {

    private static final String WORKSPACE = "X-Workspace-Id";
    private static final int BODY_MAX_BYTES = 4_096;
    private final AssistantOrganizationSso sso;
    private final AssistantAccounts accounts;
    private final AssistantOrganizations organizations;
    private final AssistantConversations conversations;
    private final ObjectReader json;

    AssistantOrganizationSsoController(AssistantOrganizationSso sso, AssistantAccounts accounts,
        AssistantOrganizations organizations, AssistantConversations conversations,
        ObjectMapper json) {
        this.sso = sso;
        this.accounts = accounts;
        this.organizations = organizations;
        this.conversations = conversations;
        this.json = AssistantPinnedJwt.objectReader(json);
    }

    @GetMapping("/status")
    ResponseEntity<Status> status() {
        return ok(new Status(sso.available()));
    }

    @GetMapping("/link")
    ResponseEntity<LinkStatus> link(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace) {
        return ok(sso.linkStatus(accounts.authenticate(authorization, workspace)));
    }

    @PostMapping(path = "/start", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RateLimiter(name = "assistant.organization-sso.start", key = "#root.args[0].remoteAddr",
        rate = 20,
        interval = 1, unit = TimeUnit.MINUTES)
    ResponseEntity<Start> start(HttpServletRequest request) {
        return ok(sso.start(text(body(request, 1), "browserBinding")));
    }

    @PostMapping(path = "/link/start", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RateLimiter(name = "assistant.organization-sso.link", key = "#root.args[0].remoteAddr",
        rate = 12,
        interval = 1, unit = TimeUnit.MINUTES)
    ResponseEntity<Start> startLink(HttpServletRequest request,
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace) {
        var principal = accounts.authenticate(authorization, workspace);
        JsonNode input = body(request, 3);
        return ok(sso.startLink(principal, text(input, "browserBinding"),
            text(input, "currentPassword"), confirm(input)));
    }

    @PostMapping(path = "/complete", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RateLimiter(name = "assistant.organization-sso.complete", key = "#root.args[0].remoteAddr",
        rate = 30,
        interval = 1, unit = TimeUnit.MINUTES)
    ResponseEntity<Completion> complete(HttpServletRequest request) {
        JsonNode input = body(request, 4);
        return ok(sso.complete(text(input, "state"), text(input, "code"), text(input, "issuer"),
            text(input, "browserBinding")));
    }

    @GetMapping("/workspace")
    ResponseEntity<Summary> workspace(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace) {
        var scope = sso.authenticate(authorization, workspace);
        Summary summary = organizations.current(new Principal(scope.user(), scope.sessionId()));
        sso.authenticate(authorization, workspace);
        return ok(summary);
    }

    @GetMapping("/conversations")
    ResponseEntity<ConversationPage> conversations(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace,
        @RequestParam(value = "limit", required = false, defaultValue = "16") Integer limit,
        @RequestParam(value = "cursor", required = false) String cursor) {
        var scope = sso.authenticate(authorization, workspace);
        if (limit < 1 || limit > 32 || cursor != null && cursor.length() > 256) {
            throw invalid();
        }
        var page = conversations.list(scope.user(),
            new ConversationQuery(null, false, limit, cursor, null, null));
        sso.authenticate(authorization, workspace);
        return ok(page);
    }

    @GetMapping("/conversations/{id}")
    ResponseEntity<Conversation> conversation(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace,
        @PathVariable("id") String id) {
        var scope = sso.authenticate(authorization, workspace);
        var conversation = conversations.get(scope.user(), id);
        sso.authenticate(authorization, workspace);
        return ok(conversation);
    }

    @PostMapping("/logout")
    ResponseEntity<Void> logout(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace) {
        sso.logout(authorization, workspace);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @PostMapping(path = "/link/revoke", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RateLimiter(name = "assistant.organization-sso.unlink", key = "#root.args[0].remoteAddr",
        rate = 8,
        interval = 1, unit = TimeUnit.MINUTES)
    ResponseEntity<Void> unlink(HttpServletRequest request,
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace) {
        var principal = accounts.authenticate(authorization, workspace);
        JsonNode input = body(request, 2);
        sso.unlink(principal, text(input, "currentPassword"), confirm(input));
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    private JsonNode body(HttpServletRequest request, int fieldCount) {
        try {
            byte[] bytes = request.getInputStream().readNBytes(BODY_MAX_BYTES + 1);
            if (bytes.length == 0 || bytes.length > BODY_MAX_BYTES) {
                throw invalid();
            }
            JsonNode parsed = json.readTree(bytes);
            if (parsed == null || !parsed.isObject() || parsed.size() != fieldCount) {
                throw invalid();
            }
            return parsed;
        } catch (IOException failure) {
            throw invalid();
        }
    }

    private static String text(JsonNode body, String field) {
        String value = body.path(field).textValue();
        if (value == null || value.isEmpty()) {
            throw invalid();
        }
        return value;
    }

    private static boolean confirm(JsonNode body) {
        JsonNode value = body.path("confirm");
        return value.isBoolean() && value.booleanValue();
    }

    private static AssistantApiException invalid() {
        return new AssistantApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
            "The organization sign-in request is invalid.");
    }

    @ExceptionHandler(AssistantApiException.class)
    ResponseEntity<ApiError> reject(AssistantApiException failure) {
        return ResponseEntity.status(failure.status()).contentType(MediaType.APPLICATION_JSON)
            .cacheControl(CacheControl.noStore())
            .body(new ApiError(failure.code(), failure.getMessage()));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiError> invalidArguments() {
        return reject(invalid());
    }

    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    ResponseEntity<ApiError> unavailable() {
        return reject(new AssistantApiException(HttpStatus.SERVICE_UNAVAILABLE,
            "ORGANIZATION_SSO_UNAVAILABLE",
            "The organization sign-in is temporarily unavailable."));
    }

    @ExceptionHandler(RateLimiterException.class)
    ResponseEntity<ApiError> throttled() {
        return reject(new AssistantApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED",
            "Retry organization sign-in later."));
    }

    private static <T> ResponseEntity<T> ok(T body) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
            .cacheControl(CacheControl.noStore()).header("Referrer-Policy", "no-referrer")
            .body(body);
    }

    private record Status(boolean available) {
    }
}

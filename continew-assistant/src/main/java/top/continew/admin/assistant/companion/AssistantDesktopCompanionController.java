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

package top.continew.admin.assistant.companion;

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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import top.continew.admin.assistant.account.AssistantAccountController.ApiError;
import top.continew.admin.assistant.account.AssistantAccounts;
import top.continew.admin.assistant.account.AssistantAccounts.Principal;
import top.continew.admin.assistant.account.AssistantApiException;
import top.continew.admin.assistant.companion.DesktopCompanionBroker.Binding;
import top.continew.admin.assistant.companion.DesktopCompanionBroker.Failure;
import top.continew.admin.assistant.companion.DesktopCompanionBroker.Offer;
import top.continew.admin.assistant.companion.DesktopCompanionBroker.State;
import top.continew.starter.extension.tenant.annotation.TenantIgnore;
import top.continew.starter.log.annotation.Log;

import jakarta.servlet.http.HttpServletRequest;
import java.util.regex.Pattern;

/**
 * Strict loopback endpoints for pairing and approving the fixed local workbench syntax action.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
@RestController
@RequestMapping("/api/assistant/v1/desktop-companion")
@ConditionalOnProperty(name = "assistant.enabled", havingValue = "true")
@ExcludeFromGracefulResponse
@SaIgnore
@TenantIgnore
@Log(ignore = true)
public class AssistantDesktopCompanionController {

    private static final Pattern UUID_TEXT =
        Pattern.compile("[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}");
    private final AssistantAccounts accounts;
    private final AssistantDesktopCompanion companion;

    /**
     * Connects the local relay to standard consumer authentication.
     *
     * @param accounts consumer authentication service
     * @param companion pairing and task relaying service
     */
    public AssistantDesktopCompanionController(AssistantAccounts accounts,
        AssistantDesktopCompanion companion) {
        this.accounts = accounts;
        this.companion = companion;
    }

    /**
     * Reads only the current session's pairing, task and available audit.
     * @param request current direct loopback actor
     * @return scoped companion state
     */
    @GetMapping
    public ResponseEntity<State> state(HttpServletRequest request) {
        return response(HttpStatus.OK,
            companion.use(actor(request), DesktopCompanionBroker::state));
    }

    /**
     * Issues one short-lived code for the current desktop session.
     * @param request current direct loopback actor
     * @return one-use offer
     */
    @PostMapping("/offers")
    public ResponseEntity<Offer> offer(HttpServletRequest request) {
        return response(HttpStatus.CREATED,
            companion.use(actor(request), DesktopCompanionBroker::offer));
    }

    /**
     * Claims a same-owner offer from a different active session.
     * @param request current direct loopback actor
     * @param body exact one-use code request
     * @return pairing awaiting desktop confirmation
     */
    @PostMapping("/offers/claim")
    public ResponseEntity<State> claim(HttpServletRequest request, @RequestBody JsonNode body) {
        Principal actor = actor(request);
        exact(body, 1);
        String code = body.path("code").textValue();
        return response(HttpStatus.OK,
            companion.use(actor, (broker, scope) -> broker.claim(scope, code)));
    }

    /**
     * Activates the binding after a separate desktop confirmation.
     * @param request current direct loopback actor
     * @param pairId pairing identifier
     * @param body explicit user confirmation
     * @return active binding
     */
    @PostMapping("/pairs/{pairId}/confirm")
    public ResponseEntity<State> confirm(HttpServletRequest request,
        @PathVariable("pairId") String pairId,
        @RequestBody JsonNode body) {
        Principal actor = actor(request);
        exact(body, 1);
        JsonNode confirmation = body.path("confirm");
        if (!confirmation.isBoolean() || !confirmation.booleanValue()) {
            throw invalid();
        }
        return response(HttpStatus.OK,
            companion.use(actor, (broker, scope) -> broker.confirm(scope, id(pairId), true)));
    }

    /**
     * Revokes the selected binding and its task.
     * @param request current direct loopback actor
     * @param pairId pairing identifier
     * @return empty response
     */
    @DeleteMapping("/pairs/{pairId}")
    public ResponseEntity<Void> revoke(HttpServletRequest request,
        @PathVariable("pairId") String pairId) {
        companion.use(actor(request), (broker, scope) -> {
            broker.revoke(scope, id(pairId));
            return null;
        });
        return empty();
    }

    /**
     * Stages the exact local syntax review on an active desktop pairing.
     * @param request current direct loopback actor
     * @param pairId pairing identifier
     * @param body exact scope, revision and digest
     * @return current task state
     */
    @PutMapping("/pairs/{pairId}/task")
    public ResponseEntity<State> publish(HttpServletRequest request,
        @PathVariable("pairId") String pairId,
        @RequestBody JsonNode body) {
        Principal actor = actor(request);
        Binding binding = parseBinding(body, 3);
        return response(HttpStatus.OK,
            companion.use(actor, (broker, scope) -> broker.publish(scope, id(pairId), binding)));
    }

    /**
     * Clears one desktop task when its local directory or revision changes.
     * @param request current direct loopback actor
     * @param pairId pairing identifier
     * @param taskId exact task identifier
     * @return empty response
     */
    @DeleteMapping("/pairs/{pairId}/task/{taskId}")
    public ResponseEntity<Void> withdraw(HttpServletRequest request,
        @PathVariable("pairId") String pairId,
        @PathVariable("taskId") String taskId) {
        companion.use(actor(request), (broker, scope) -> {
            broker.withdraw(scope, id(pairId), id(taskId));
            return null;
        });
        return empty();
    }

    /**
     * Records the paired mobile session's explicit decision on the current task.
     * @param request current direct loopback actor
     * @param pairId pairing identifier
     * @param taskId exact task identifier
     * @param body exact task binding and decision
     * @return authorized task state
     */
    @PostMapping("/pairs/{pairId}/task/{taskId}/decision")
    public ResponseEntity<State> decide(HttpServletRequest request,
        @PathVariable("pairId") String pairId,
        @PathVariable("taskId") String taskId, @RequestBody JsonNode body) {
        Principal actor = actor(request);
        Binding binding = parseBinding(body, 4);
        String decision = text(body, "decision");
        return response(HttpStatus.OK, companion.use(actor,
            (broker, scope) -> broker.decide(scope, id(pairId), id(taskId), binding, decision)));
    }

    /**
     * Consumes one already-approved binding for a voluntary desktop validation.
     * @param request current direct loopback actor
     * @param pairId pairing identifier
     * @param taskId exact task identifier
     * @param body exact approved binding
     * @return empty response
     */
    @PostMapping("/pairs/{pairId}/task/{taskId}/consume")
    public ResponseEntity<Void> consume(HttpServletRequest request,
        @PathVariable("pairId") String pairId,
        @PathVariable("taskId") String taskId, @RequestBody JsonNode body) {
        Principal actor = actor(request);
        Binding binding = parseBinding(body, 3);
        companion.use(actor, (broker, scope) -> {
            broker.consume(scope, id(pairId), id(taskId), binding);
            return null;
        });
        return empty();
    }

    private Principal actor(HttpServletRequest request) {
        if (!AssistantDesktopCompanion.loopback(request.getRemoteAddr())
            || !AssistantDesktopCompanion.loopback(request.getLocalAddr())
            || request.getHeader("Forwarded") != null
            || request.getHeader("X-Forwarded-For") != null
            || request.getHeader("X-Forwarded-Host") != null
            || request.getHeader("X-Forwarded-Proto") != null) {
            throw AssistantDesktopCompanion.unavailable();
        }
        return accounts.authenticate(request.getHeader(HttpHeaders.AUTHORIZATION),
            request.getHeader("X-Workspace-Id"));
    }

    private static Binding parseBinding(JsonNode body, int count) {
        exact(body, count);
        JsonNode revision = body.path("revision");
        if (!revision.isIntegralNumber() || !revision.canConvertToInt()) {
            throw invalid();
        }
        return new Binding(text(body, "scopeId"), revision.intValue(), text(body, "sourceSha256"));
    }

    private static void exact(JsonNode body, int count) {
        if (body == null || !body.isObject() || body.size() != count) {
            throw invalid();
        }
    }

    private static String text(JsonNode body, String field) {
        JsonNode node = body.path(field);
        if (!node.isTextual() || node.textValue().length() > 64) {
            throw invalid();
        }
        return node.textValue();
    }

    private static String id(String value) {
        if (!UUID_TEXT.matcher(value).matches()) {
            throw AssistantDesktopCompanion.unavailable();
        }
        return value;
    }

    private static AssistantApiException invalid() {
        return new AssistantApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
            "Invalid desktop companion request.");
    }

    /**
     * Maps consumer scope errors without revealing protected data.
     * @param error expected consumer error
     * @return no-store response
     */
    @ExceptionHandler(AssistantApiException.class)
    public ResponseEntity<ApiError> authentication(AssistantApiException error) {
        return response(error.status(), new ApiError(error.code(), error.getMessage()));
    }

    /**
     * Maps process-local validation and authorization outcomes.
     * @param error expected relay error
     * @return no-store response
     */
    @ExceptionHandler(Failure.class)
    public ResponseEntity<ApiError> broker(Failure error) {
        String code = switch (error.status) {
            case 400 -> "VALIDATION_ERROR";
            case 404 -> "NOT_FOUND";
            case 409 -> "CONFLICT";
            case 429 -> "RATE_LIMITED";
            default -> "INTERNAL_ERROR";
        };
        return response(HttpStatus.valueOf(error.status), new ApiError(code, error.getMessage()));
    }

    /**
     * Maps invalid entity bodies to a fixed error.
     * @return no-store response
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> malformed() {
        return authentication(invalid());
    }

    /**
     * Fails closed if the database cannot recheck the devices.
     * @return no-store response
     */
    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    public ResponseEntity<ApiError> storage() {
        return response(HttpStatus.SERVICE_UNAVAILABLE,
            new ApiError("CAPABILITY_UNAVAILABLE", "Local desktop companion unavailable."));
    }

    private static <T> ResponseEntity<T> response(HttpStatus status, T body) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(body);
    }

    private static ResponseEntity<Void> empty() {
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}

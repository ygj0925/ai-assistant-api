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

package top.continew.admin.assistant.connector;

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
import top.continew.admin.assistant.connector.AssistantSandboxConnector.Action;
import top.continew.admin.assistant.connector.AssistantSandboxConnector.Authorization;
import top.continew.admin.assistant.connector.AssistantSandboxConnector.Page;
import top.continew.starter.extension.tenant.annotation.TenantIgnore;
import top.continew.starter.log.annotation.Log;

/**
 * Authenticated, local-only simulator approval and result-verification endpoints.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
@RestController
@RequestMapping("/api/assistant/v1/automations/sandbox-connector")
@ConditionalOnProperty(name = "assistant.enabled", havingValue = "true")
@ExcludeFromGracefulResponse
@SaIgnore
@TenantIgnore
@Log(ignore = true)
public class AssistantSandboxConnectorController {

    private static final String WORKSPACE = "X-Workspace-Id";
    private static final String KEY = "Idempotency-Key";

    private final AssistantAccounts accounts;
    private final AssistantSandboxConnector connector;

    /**
     * Links the API to current sessions and the isolated simulator.
     *
     * @param accounts consumer authentication
     * @param connector simulator service
     */
    public AssistantSandboxConnectorController(AssistantAccounts accounts,
        AssistantSandboxConnector connector) {
        this.accounts = accounts;
        this.connector = connector;
    }

    /**
     * Reads the current sandbox authorization.
     *
     * @param bearer consumer credentials
     * @param workspace selected workspace
     * @return authorization status
     */
    @GetMapping("/authorization")
    public ResponseEntity<Authorization> authorization(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String bearer,
        @RequestHeader(value = WORKSPACE, required = false) String workspace) {
        return response(HttpStatus.OK,
            connector.authorization(accounts.authenticate(bearer, workspace)));
    }

    /**
     * Accepts explicit local simulator consent.
     *
     * @param bearer consumer credentials
     * @param workspace selected workspace
     * @param body exact confirmation
     * @return authorized generation
     */
    @PostMapping("/authorization")
    public ResponseEntity<Authorization> authorize(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String bearer,
        @RequestHeader(value = WORKSPACE, required = false) String workspace,
        @RequestBody JsonNode body) {
        var actor = accounts.authenticate(bearer, workspace);
        exact(body, 1);
        confirmed(body);
        return response(HttpStatus.OK, connector.authorize(actor));
    }

    /**
     * Revokes one scoped authorization generation.
     *
     * @param bearer consumer credentials
     * @param workspace selected workspace
     * @param id authorization identifier
     * @return empty response
     */
    @DeleteMapping("/authorization/{id}")
    public ResponseEntity<Void> revoke(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String bearer,
        @RequestHeader(value = WORKSPACE, required = false) String workspace,
        @PathVariable("id") String id) {
        connector.revoke(accounts.authenticate(bearer, workspace), id);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    /**
     * Stages a fixed local action for explicit approval.
     *
     * @param bearer consumer credentials
     * @param workspace selected workspace
     * @param key caller retry key
     * @param body exact effect and acknowledgement scenario
     * @return staged action
     */
    @PostMapping("/actions")
    public ResponseEntity<Action> create(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String bearer,
        @RequestHeader(value = WORKSPACE, required = false) String workspace,
        @RequestHeader(value = KEY, required = false) String key,
        @RequestBody JsonNode body) {
        var actor = accounts.authenticate(bearer, workspace);
        exact(body, 3);
        if (!AssistantSandboxConnector.KIND.equals(text(body, "kind"))) {
            throw invalid();
        }
        return response(HttpStatus.CREATED,
            connector.create(actor, key, text(body, "authorizationId"), text(body, "simulation")));
    }

    /**
     * Lists the current workspace's local actions.
     *
     * @param bearer consumer credentials
     * @param workspace selected workspace
     * @param limit requested page size
     * @param cursor continuation cursor
     * @return bounded action page
     */
    @GetMapping("/actions")
    public ResponseEntity<Page> list(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String bearer,
        @RequestHeader(value = WORKSPACE, required = false) String workspace,
        @RequestParam(value = "limit", required = false) Integer limit,
        @RequestParam(value = "cursor", required = false) String cursor) {
        return response(HttpStatus.OK,
            connector.list(accounts.authenticate(bearer, workspace), limit, cursor));
    }

    /**
     * Reads the current status of an owned action.
     *
     * @param bearer consumer credentials
     * @param workspace selected workspace
     * @param id action identifier
     * @return current action
     */
    @GetMapping("/actions/{id}")
    public ResponseEntity<Action> get(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String bearer,
        @RequestHeader(value = WORKSPACE, required = false) String workspace,
        @PathVariable("id") String id) {
        return response(HttpStatus.OK, connector.get(accounts.authenticate(bearer, workspace), id));
    }

    /**
     * Accepts confirmation of an exact reviewed local action.
     *
     * @param bearer consumer credentials
     * @param workspace selected workspace
     * @param id action identifier
     * @param body review confirmation
     * @return accepted action
     */
    @PostMapping("/actions/{id}/approve")
    public ResponseEntity<Action> approve(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String bearer,
        @RequestHeader(value = WORKSPACE, required = false) String workspace,
        @PathVariable("id") String id, @RequestBody JsonNode body) {
        var actor = accounts.authenticate(bearer, workspace);
        exact(body, 2);
        confirmed(body);
        return response(HttpStatus.ACCEPTED,
            connector.approve(actor, id, text(body, "approvalRevision")));
    }

    /**
     * Withdraws an owned operation before dispatch.
     *
     * @param bearer consumer credentials
     * @param workspace selected workspace
     * @param id action identifier
     * @return current action
     */
    @PostMapping("/actions/{id}/cancel")
    public ResponseEntity<Action> cancel(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String bearer,
        @RequestHeader(value = WORKSPACE, required = false) String workspace,
        @PathVariable("id") String id) {
        return response(HttpStatus.OK,
            connector.cancel(accounts.authenticate(bearer, workspace), id));
    }

    /**
     * Checks the independent receipt for an owned action.
     *
     * @param bearer consumer credentials
     * @param workspace selected workspace
     * @param id action identifier
     * @return current verified status
     */
    @PostMapping("/actions/{id}/reconcile")
    public ResponseEntity<Action> reconcile(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String bearer,
        @RequestHeader(value = WORKSPACE, required = false) String workspace,
        @PathVariable("id") String id) {
        return response(HttpStatus.OK,
            connector.reconcile(accounts.authenticate(bearer, workspace), id));
    }

    /**
     * Maps a scoped consumer exception.
     *
     * @param error safe application error
     * @return consumer error
     */
    @ExceptionHandler(AssistantApiException.class)
    public ResponseEntity<ApiError> handle(AssistantApiException error) {
        return response(error.status(), new ApiError(error.code(), error.getMessage()));
    }

    /**
     * Rejects a malformed request or query parameter.
     *
     * @return validation error
     */
    @ExceptionHandler({HttpMessageNotReadableException.class,
        MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiError> malformed() {
        return handle(invalid());
    }

    /**
     * Handles an unavailable local operation store.
     *
     * @return consumer storage error
     */
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ApiError> storage() {
        return response(HttpStatus.INTERNAL_SERVER_ERROR,
            new ApiError("INTERNAL_ERROR", "The local connector operation is unavailable."));
    }

    private static void exact(JsonNode body, int fields) {
        if (body == null || !body.isObject() || body.size() != fields) {
            throw invalid();
        }
    }

    private static void confirmed(JsonNode body) {
        JsonNode item = body.get("confirm");
        if (item == null || !item.isBoolean() || !item.booleanValue()) {
            throw invalid();
        }
    }

    private static String text(JsonNode body, String field) {
        JsonNode item = body.get(field);
        if (item == null || !item.isTextual()) {
            throw invalid();
        }
        return item.textValue();
    }

    private static AssistantApiException invalid() {
        return new AssistantApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
            "An exact local simulator request or confirmation is required.");
    }

    private static <T> ResponseEntity<T> response(HttpStatus status, T payload) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(payload);
    }
}

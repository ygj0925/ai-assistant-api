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

package top.continew.admin.assistant.reminder;

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
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import top.continew.admin.assistant.account.AssistantAccountController.ApiError;
import top.continew.admin.assistant.account.AssistantAccounts;
import top.continew.admin.assistant.account.AssistantApiException;
import top.continew.admin.assistant.reminder.AssistantProactiveAssistant.Preferences;
import top.continew.admin.assistant.reminder.AssistantProactiveAssistant.Settings;
import top.continew.admin.assistant.reminder.AssistantProactiveAssistant.Suggestions;
import top.continew.admin.assistant.reminder.AssistantReminders.QuietHours;
import top.continew.starter.extension.tenant.annotation.TenantIgnore;
import top.continew.starter.log.annotation.Log;

/**
 * Consumer preferences and private calendar suggestions for the local proactive assistant.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
@RestController
@RequestMapping("/api/assistant/v1/automations/proactive")
@ConditionalOnProperty(name = "assistant.enabled", havingValue = "true")
@ExcludeFromGracefulResponse
@SaIgnore
@TenantIgnore
@Log(ignore = true)
public class AssistantProactiveController {

    private static final String WORKSPACE = "X-Workspace-Id";

    private final AssistantAccounts accounts;
    private final AssistantProactiveAssistant assistant;

    /**
     * Connects consumer authentication to private preferences and suggestions.
     *
     * @param accounts consumer sessions
     * @param assistant local opted-in suggestions
     */
    public AssistantProactiveController(AssistantAccounts accounts,
        AssistantProactiveAssistant assistant) {
        this.accounts = accounts;
        this.assistant = assistant;
    }

    /**
     * Reads current saved policy without opting in the account.
     *
     * @param authorization consumer credentials
     * @param workspace selected workspace
     * @return current preferences
     */
    @GetMapping("/preferences")
    public ResponseEntity<Preferences> preferences(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace) {
        return response(HttpStatus.OK,
            assistant.preferences(accounts.authenticate(authorization, workspace)));
    }

    /**
     * Saves one explicit user policy selection.
     *
     * @param authorization consumer credentials
     * @param workspace selected workspace
     * @param body exact preferences
     * @return saved policy
     */
    @PutMapping("/preferences")
    public ResponseEntity<Preferences> update(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace,
        @RequestBody JsonNode body) {
        var actor = accounts.authenticate(authorization, workspace);
        exact(body, body != null && body.has("quietHours") ? 5 : 4);
        JsonNode enable = body.get("enabled");
        if (enable == null || !enable.isBoolean()) {
            throw invalid();
        }
        JsonNode window = body.get("quietHours");
        QuietHours quiet = null;
        if (window != null && !window.isNull()) {
            exact(window, 2);
            quiet = new QuietHours(text(window, "start"), text(window, "end"));
        }
        Settings settings = new Settings(enable.booleanValue(), text(body, "role"),
            text(body, "notification"), text(body, "timeZone"), quiet);
        return response(HttpStatus.OK, assistant.update(actor, settings));
    }

    /**
     * Returns current authorized suggestions for this workspace.
     *
     * @param authorization consumer credentials
     * @param workspace selected workspace
     * @return eligible in-app suggestions
     */
    @GetMapping("/suggestions")
    public ResponseEntity<Suggestions> suggestions(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace) {
        return response(HttpStatus.OK,
            assistant.suggestions(accounts.authenticate(authorization, workspace)));
    }

    /**
     * Permanently dismisses one owned suggestion.
     *
     * @param authorization consumer credentials
     * @param workspace selected workspace
     * @param id exact suggestion identifier
     * @return empty response
     */
    @PostMapping("/suggestions/{id}/dismiss")
    public ResponseEntity<Void> dismiss(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace,
        @PathVariable("id") String id) {
        assistant.dismiss(accounts.authenticate(authorization, workspace), id);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    /**
     * Maps a scoped consumer failure to the usual error format.
     *
     * @param error safe application failure
     * @return error payload
     */
    @ExceptionHandler(AssistantApiException.class)
    public ResponseEntity<ApiError> handle(AssistantApiException error) {
        return response(error.status(), new ApiError(error.code(), error.getMessage()));
    }

    /**
     * Rejects malformed opt-in settings.
     *
     * @return validation error
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> malformed() {
        return handle(invalid());
    }

    /**
     * Returns an unavailable storage error without private details.
     *
     * @return storage error
     */
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ApiError> storage() {
        return response(HttpStatus.INTERNAL_SERVER_ERROR,
            new ApiError("INTERNAL_ERROR", "Local proactive suggestions are unavailable."));
    }

    private static String text(JsonNode body, String field) {
        JsonNode item = body.get(field);
        if (item == null || !item.isTextual()) {
            throw invalid();
        }
        return item.textValue();
    }

    private static void exact(JsonNode body, int fields) {
        if (body == null || !body.isObject() || body.size() != fields) {
            throw invalid();
        }
    }

    private static AssistantApiException invalid() {
        return new AssistantApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
            "An exact proactive policy is required.");
    }

    private static <T> ResponseEntity<T> response(HttpStatus status, T payload) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(payload);
    }
}

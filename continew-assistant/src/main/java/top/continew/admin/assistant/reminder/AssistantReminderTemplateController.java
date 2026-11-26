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
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import top.continew.admin.assistant.account.AssistantAccountController.ApiError;
import top.continew.admin.assistant.account.AssistantAccounts;
import top.continew.admin.assistant.account.AssistantApiException;
import top.continew.admin.assistant.reminder.AssistantReminderTemplates.Preview;
import top.continew.admin.assistant.reminder.AssistantReminderTemplates.Published;
import top.continew.admin.assistant.reminder.AssistantReminderTemplates.ShareList;
import top.continew.admin.assistant.reminder.AssistantReminderTemplates.View;
import top.continew.admin.assistant.reminder.AssistantReminders.Reminder;
import top.continew.starter.extension.tenant.annotation.TenantIgnore;
import top.continew.starter.log.annotation.Log;

/**
 * Receives and redeems explicit reminder invitations without request logging.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
@RestController
@RequestMapping("/api/assistant/v1/automations")
@ConditionalOnProperty(name = "assistant.enabled", havingValue = "true")
@ExcludeFromGracefulResponse
@SaIgnore
@TenantIgnore
@Log(ignore = true)
public class AssistantReminderTemplateController {

    private static final String WORKSPACE = "X-Workspace-Id";

    private final AssistantAccounts accounts;
    private final AssistantReminderTemplates templates;

    /**
     * Wires protected template operations.
     *
     * @param accounts consumer sessions and current workspace membership
     * @param templates reminder templates
     */
    public AssistantReminderTemplateController(AssistantAccounts accounts,
        AssistantReminderTemplates templates) {
        this.accounts = accounts;
        this.templates = templates;
    }

    /**
     * Previews the exact fields the signed-in owner can publish.
     *
     * @param authorization consumer credentials
     * @param workspace owner workspace
     * @param id source reminder
     * @return reviewed schedule
     */
    @PostMapping("/reminders/{id}/share-preview")
    public ResponseEntity<Preview> preview(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace,
        @PathVariable("id") String id) {
        var principal = accounts.authenticate(authorization, workspace);
        return response(HttpStatus.OK, templates.preview(principal, id));
    }

    /**
     * Issues the one-time clear-text invitation receipt.
     *
     * @param authorization consumer credentials
     * @param workspace owner workspace
     * @param id source reminder
     * @param input reviewed revision only
     * @return publication receipt
     */
    @PostMapping("/reminders/{id}/shares")
    public ResponseEntity<Published> publish(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace,
        @PathVariable("id") String id,
        @RequestBody(required = false) JsonNode input) {
        var principal = accounts.authenticate(authorization, workspace);
        requireObject(input, 1);
        return response(HttpStatus.CREATED, templates.publish(principal, id,
            text(input, "revision")));
    }

    /**
     * Lists owner-visible invitations without exposing tokens.
     *
     * @param authorization consumer credentials
     * @param workspace selected workspace
     * @param id source reminder
     * @return invitation metadata
     */
    @GetMapping("/reminders/{id}/shares")
    public ResponseEntity<ShareList> list(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace,
        @PathVariable("id") String id) {
        var principal = accounts.authenticate(authorization, workspace);
        return response(HttpStatus.OK, templates.list(principal, id));
    }

    /**
     * Retires the specified invitation durably.
     *
     * @param authorization consumer credentials
     * @param workspace selected workspace
     * @param id owner-managed invitation ID
     * @return empty success response
     */
    @DeleteMapping("/reminder-shares/{id}")
    public ResponseEntity<Void> revoke(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace,
        @PathVariable("id") String id) {
        var principal = accounts.authenticate(authorization, workspace);
        templates.revoke(principal, id);
        return response(HttpStatus.NO_CONTENT, null);
    }

    /**
     * Displays a live invitation to an authenticated viewer.
     *
     * @param authorization consumer credentials
     * @param workspace recipient workspace
     * @param input token only
     * @return recipient preview and confirmation scope
     */
    @PostMapping("/reminder-shares/read")
    public ResponseEntity<View> read(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace,
        @RequestBody(required = false) JsonNode input) {
        var principal = accounts.authenticate(authorization, workspace);
        requireObject(input, 1);
        return response(HttpStatus.OK, templates.read(principal, text(input, "token")));
    }

    /**
     * Copies a preview after explicit recipient confirmation.
     *
     * @param authorization consumer credentials
     * @param workspace destination workspace
     * @param input token, scoped revision and affirmative confirmation
     * @return independent reminder
     */
    @PostMapping("/reminder-shares/apply")
    public ResponseEntity<Reminder> apply(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace,
        @RequestBody(required = false) JsonNode input) {
        var principal = accounts.authenticate(authorization, workspace);
        requireObject(input, 3);
        JsonNode confirm = input.path("confirm");
        if (!confirm.isBoolean()) {
            throw invalid();
        }
        return response(HttpStatus.CREATED, templates.apply(principal, text(input, "token"),
            text(input, "revision"), confirm.booleanValue()));
    }

    /**
     * Returns a sanitized typed API error.
     *
     * @param error public error
     * @return non-cacheable failure
     */
    @ExceptionHandler(AssistantApiException.class)
    public ResponseEntity<ApiError> handleError(AssistantApiException error) {
        return response(error.status(), new ApiError(error.code(), error.getMessage()));
    }

    /**
     * Rejects invalid input without reflecting the invitation.
     *
     * @return non-cacheable validation failure
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleInvalid() {
        return handleError(invalid());
    }

    /**
     * Conceals datastore and transaction details.
     *
     * @return non-cacheable storage failure
     */
    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    public ResponseEntity<ApiError> handleStorage() {
        return response(HttpStatus.SERVICE_UNAVAILABLE,
            new ApiError("INTERNAL_ERROR", "Reminder templates are temporarily unavailable."));
    }

    private static void requireObject(JsonNode input, int fields) {
        if (input == null || !input.isObject() || input.size() != fields) {
            throw invalid();
        }
    }

    private static String text(JsonNode object, String field) {
        JsonNode value = object.path(field);
        if (!value.isTextual()) {
            throw invalid();
        }
        return value.textValue();
    }

    private static AssistantApiException invalid() {
        return new AssistantApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
            "Invalid reminder template request.");
    }

    private static <T> ResponseEntity<T> response(HttpStatus status, T body) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(body);
    }
}

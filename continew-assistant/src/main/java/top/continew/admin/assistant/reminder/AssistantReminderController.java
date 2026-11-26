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
import org.springframework.web.bind.annotation.PatchMapping;
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
import top.continew.admin.assistant.reminder.AssistantReminders.Create;
import top.continew.admin.assistant.reminder.AssistantReminders.Occurrence;
import top.continew.admin.assistant.reminder.AssistantReminders.Page;
import top.continew.admin.assistant.reminder.AssistantReminders.QuietHours;
import top.continew.admin.assistant.reminder.AssistantReminders.Reminder;
import top.continew.starter.extension.tenant.annotation.TenantIgnore;
import top.continew.starter.log.annotation.Log;

/**
 * Authenticated calendar reminders and in-app notices.
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
public class AssistantReminderController {

    private static final String WORKSPACE = "X-Workspace-Id";

    private final AssistantAccounts accounts;
    private final AssistantReminders reminders;

    /**
     * Wires scoped consumer authorization and durable local schedules.
     *
     * @param accounts account and workspace authentication
     * @param reminders local reminder service
     */
    public AssistantReminderController(AssistantAccounts accounts, AssistantReminders reminders) {
        this.accounts = accounts;
        this.reminders = reminders;
    }

    /**
     * Creates an idempotent owner schedule.
     *
     * @param authorization consumer bearer header
     * @param workspace chosen workspace
     * @param key client UUID for retry
     * @param body calendar input
     * @return created reminder or its original replay
     */
    @PostMapping("/reminders")
    public ResponseEntity<Reminder> create(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace,
        @RequestHeader(value = "Idempotency-Key", required = false) String key,
        @RequestBody(required = false) JsonNode body) {
        var owner = accounts.authenticate(authorization, workspace).user();
        if (body == null || !body.isObject() || body.size() != (body.has("quietHours") ? 5 : 4)
            || !body.path("title").isTextual() || !body.path("startsAtLocal").isTextual()
            || !body.path("timeZone").isTextual() || !body.path("cadence").isTextual()) {
            throw invalid();
        }
        QuietHours quiet = null;
        if (body.has("quietHours")) {
            var boundaries = body.get("quietHours");
            if (!boundaries.isObject() || boundaries.size() != 2
                || !boundaries.path("start").isTextual() || !boundaries.path("end").isTextual()) {
                throw invalid();
            }
            quiet = new QuietHours(boundaries.get("start").textValue(),
                boundaries.get("end").textValue());
        }
        return response(HttpStatus.CREATED, reminders.create(owner, key,
            new Create(body.get("title").textValue(), body.get("startsAtLocal").textValue(),
                body.get("timeZone").textValue(), body.get("cadence").textValue(), quiet)));
    }

    /**
     * Lists the owner's reminders.
     *
     * @param authorization consumer bearer header
     * @param workspace chosen workspace
     * @param limit page size
     * @param cursor opaque continuation
     * @return reminder page
     */
    @GetMapping("/reminders")
    public ResponseEntity<Page<Reminder>> list(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace,
        @RequestParam(value = "limit", required = false) Integer limit,
        @RequestParam(value = "cursor", required = false) String cursor) {
        var owner = accounts.authenticate(authorization, workspace).user();
        return response(HttpStatus.OK, reminders.list(owner, cursor, limit));
    }

    /**
     * Reads an owned reminder's current state.
     *
     * @param authorization consumer bearer header
     * @param workspace chosen workspace
     * @param id reminder identifier
     * @return current record
     */
    @GetMapping("/reminders/{reminderId}")
    public ResponseEntity<Reminder> get(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace,
        @PathVariable("reminderId") String id) {
        var owner = accounts.authenticate(authorization, workspace).user();
        return response(HttpStatus.OK, reminders.get(owner, id));
    }

    /**
     * Pauses or explicitly resumes an owned record.
     *
     * @param authorization consumer bearer header
     * @param workspace chosen workspace
     * @param id reminder identifier
     * @param body desired active flag
     * @return resulting reminder
     */
    @PatchMapping("/reminders/{reminderId}")
    public ResponseEntity<Reminder> setActive(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace,
        @PathVariable("reminderId") String id,
        @RequestBody(required = false) JsonNode body) {
        var owner = accounts.authenticate(authorization, workspace).user();
        if (body == null || !body.isObject() || body.size() != 1
            || !body.path("active").isBoolean()) {
            throw invalid();
        }
        return response(HttpStatus.OK,
            reminders.setActive(owner, id, body.get("active").booleanValue()));
    }

    /**
     * Cancels and erases an owned reminder and notices.
     *
     * @param authorization consumer bearer header
     * @param workspace chosen workspace
     * @param id reminder to erase
     * @return empty response
     */
    @DeleteMapping("/reminders/{reminderId}")
    public ResponseEntity<Void> delete(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace,
        @PathVariable("reminderId") String id) {
        var owner = accounts.authenticate(authorization, workspace).user();
        reminders.delete(owner, id);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    /**
     * Shows durable notices and run outcomes in the selected workspace.
     *
     * @param authorization consumer bearer header
     * @param workspace chosen workspace
     * @param filter optional owned reminder filter
     * @param limit page size
     * @param cursor opaque continuation
     * @return occurrence page
     */
    @GetMapping("/reminder-occurrences")
    public ResponseEntity<Page<Occurrence>> occurrences(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace,
        @RequestParam(value = "reminderId", required = false) String filter,
        @RequestParam(value = "limit", required = false) Integer limit,
        @RequestParam(value = "cursor", required = false) String cursor) {
        var owner = accounts.authenticate(authorization, workspace).user();
        return response(HttpStatus.OK, reminders.occurrences(owner, filter, cursor, limit));
    }

    /**
     * Acknowledges an owned occurrence.
     *
     * @param authorization consumer bearer header
     * @param workspace chosen workspace
     * @param id occurrence identifier
     * @param body explicit read acknowledgement
     * @return stored occurrence
     */
    @PatchMapping("/reminder-occurrences/{occurrenceId}")
    public ResponseEntity<Occurrence> markRead(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace,
        @PathVariable("occurrenceId") String id,
        @RequestBody(required = false) JsonNode body) {
        var owner = accounts.authenticate(authorization, workspace).user();
        if (body == null || !body.isObject() || body.size() != 1
            || !body.path("read").isBoolean() || !body.get("read").booleanValue()) {
            throw invalid();
        }
        return response(HttpStatus.OK, reminders.markRead(owner, id));
    }

    /**
     * Returns a sanitized consumer error.
     *
     * @param error expected request failure
     * @return typed failure
     */
    @ExceptionHandler(AssistantApiException.class)
    public ResponseEntity<ApiError> handle(AssistantApiException error) {
        return response(error.status(), new ApiError(error.code(), error.getMessage()));
    }

    /**
     * Rejects malformed requests without echoing any supplied data.
     *
     * @return validation failure
     */
    @ExceptionHandler({HttpMessageNotReadableException.class,
        MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiError> malformed() {
        return handle(invalid());
    }

    /**
     * Conceals database failures.
     *
     * @return temporary availability failure
     */
    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    public ResponseEntity<ApiError> unavailable() {
        return response(HttpStatus.SERVICE_UNAVAILABLE,
            new ApiError("CAPABILITY_UNAVAILABLE",
                "Scheduled reminders are temporarily unavailable."));
    }

    private static AssistantApiException invalid() {
        return new AssistantApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
            "Invalid reminder request.");
    }

    private static <T> ResponseEntity<T> response(HttpStatus status, T body) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(body);
    }
}

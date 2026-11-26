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
import top.continew.admin.assistant.reminder.AssistantEventReminders.Create;
import top.continew.admin.assistant.reminder.AssistantEventReminders.Event;
import top.continew.admin.assistant.reminder.AssistantEventReminders.Occurrence;
import top.continew.admin.assistant.reminder.AssistantEventReminders.OccurrencePage;
import top.continew.admin.assistant.reminder.AssistantEventReminders.Receipt;
import top.continew.admin.assistant.reminder.AssistantEventReminders.Subscription;
import top.continew.admin.assistant.reminder.AssistantEventReminders.SubscriptionList;
import top.continew.admin.assistant.reminder.AssistantReminders.QuietHours;
import top.continew.starter.extension.tenant.annotation.TenantIgnore;
import top.continew.starter.log.annotation.Log;

/**
 * Consumer endpoints for simulator-driven in-app event reminders.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
@RestController
@RequestMapping("/api/assistant/v1/automations/event-reminders")
@ConditionalOnProperty(name = "assistant.enabled", havingValue = "true")
@ExcludeFromGracefulResponse
@SaIgnore
@TenantIgnore
@Log(ignore = true)
public class AssistantEventReminderController {

    private static final String WORKSPACE = "X-Workspace-Id";

    private final AssistantAccounts accounts;
    private final AssistantEventReminders reminders;

    /**
     * Connects consumer authorization to the event reminder service.
     *
     * @param accounts consumer sessions
     * @param reminders authorized event subscriptions
     */
    public AssistantEventReminderController(AssistantAccounts accounts,
        AssistantEventReminders reminders) {
        this.accounts = accounts;
        this.reminders = reminders;
    }

    /**
     * Lists current subscriptions in the selected scope.
     *
     * @param authorization consumer credentials
     * @param workspace selected workspace
     * @return scoped list
     */
    @GetMapping
    public ResponseEntity<SubscriptionList> list(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace) {
        return response(HttpStatus.OK,
            reminders.list(accounts.authenticate(authorization, workspace)));
    }

    /**
     * Creates a reminder for an authorized simulator receipt source.
     *
     * @param authorization consumer credentials
     * @param workspace selected workspace
     * @param body exact subscription configuration
     * @return created subscription
     */
    @PostMapping
    public ResponseEntity<Subscription> create(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace,
        @RequestBody JsonNode body) {
        var actor = accounts.authenticate(authorization, workspace);
        int expected = body != null && body.has("quietHours") ? 5 : 4;
        exact(body, expected);
        JsonNode quietNode = body.get("quietHours");
        QuietHours quiet = null;
        if (quietNode != null && !quietNode.isNull()) {
            exact(quietNode, 2);
            quiet = new QuietHours(text(quietNode, "start"), text(quietNode, "end"));
        }
        var input = new Create(text(body, "title"), text(body, "authorizationId"),
            text(body, "eventKind"), text(body, "timeZone"), quiet);
        return response(HttpStatus.CREATED, reminders.create(actor, input));
    }

    /**
     * Applies one scoped state decision.
     *
     * @param authorization consumer credentials
     * @param workspace selected workspace
     * @param id target subscription
     * @param body desired active state
     * @return current subscription
     */
    @PatchMapping("/{id}/state")
    public ResponseEntity<Subscription> state(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace,
        @PathVariable("id") String id, @RequestBody JsonNode body) {
        var actor = accounts.authenticate(authorization, workspace);
        exact(body, 1);
        JsonNode active = body.get("active");
        if (active == null || !active.isBoolean()) {
            throw invalid();
        }
        return response(HttpStatus.OK, reminders.setActive(actor, id, active.booleanValue()));
    }

    /**
     * Erases one owned reminder and its occurrences.
     *
     * @param authorization consumer credentials
     * @param workspace selected workspace
     * @param id target subscription
     * @return empty response
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace,
        @PathVariable("id") String id) {
        reminders.delete(accounts.authenticate(authorization, workspace), id);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    /**
     * Accepts the identifier of an existing, independently verified simulator effect.
     *
     * @param authorization consumer credentials
     * @param workspace selected workspace
     * @param body exact trusted receipt identifiers
     * @return deduplicated handling result
     */
    @PostMapping("/receive")
    public ResponseEntity<Receipt> receive(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace,
        @RequestBody JsonNode body) {
        var actor = accounts.authenticate(authorization, workspace);
        exact(body, 2);
        var event = new Event(text(body, "authorizationId"), text(body, "operationId"));
        return response(HttpStatus.OK, reminders.receive(actor, event));
    }

    /**
     * Reads retained outcomes for one current subscription.
     *
     * @param authorization consumer credentials
     * @param workspace selected workspace
     * @param id target subscription
     * @param limit maximum requested page size
     * @param cursor restricted continuation
     * @return current occurrence page
     */
    @GetMapping("/{id}/occurrences")
    public ResponseEntity<OccurrencePage> occurrences(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace,
        @PathVariable("id") String id,
        @RequestParam(value = "limit", defaultValue = "25") int limit,
        @RequestParam(value = "cursor", required = false) String cursor) {
        var actor = accounts.authenticate(authorization, workspace);
        return response(HttpStatus.OK, reminders.occurrences(actor, id, limit, cursor));
    }

    /**
     * Marks one scoped delivered occurrence as read.
     *
     * @param authorization consumer credentials
     * @param workspace selected workspace
     * @param id target subscription
     * @param occurrenceId delivered occurrence
     * @return current occurrence
     */
    @PostMapping("/{id}/occurrences/{occurrenceId}/read")
    public ResponseEntity<Occurrence> read(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace,
        @PathVariable("id") String id, @PathVariable("occurrenceId") String occurrenceId) {
        var actor = accounts.authenticate(authorization, workspace);
        return response(HttpStatus.OK, reminders.read(actor, id, occurrenceId));
    }

    /**
     * Maps scoped consumer errors.
     *
     * @param error consumer failure
     * @return safe error
     */
    @ExceptionHandler(AssistantApiException.class)
    public ResponseEntity<ApiError> handle(AssistantApiException error) {
        return response(error.status(), new ApiError(error.code(), error.getMessage()));
    }

    /**
     * Handles malformed fields and invalid page sizes.
     *
     * @return validation error
     */
    @ExceptionHandler({HttpMessageNotReadableException.class,
        MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiError> malformed() {
        return handle(invalid());
    }

    /**
     * Handles unavailable storage without returning details.
     *
     * @return storage error
     */
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ApiError> storage() {
        return response(HttpStatus.INTERNAL_SERVER_ERROR,
            new ApiError("INTERNAL_ERROR", "The event reminder is unavailable."));
    }

    private static void exact(JsonNode body, int size) {
        if (body == null || !body.isObject() || body.size() != size) {
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
            "An exact event reminder configuration or receipt is required.");
    }

    private static <T> ResponseEntity<T> response(HttpStatus status, T payload) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(payload);
    }
}

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

package top.continew.admin.assistant.eligibility;

import cn.dev33.satoken.annotation.SaIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import com.feiniaojin.gracefulresponse.api.ExcludeFromGracefulResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import top.continew.admin.assistant.account.AssistantAccountController.ApiError;
import top.continew.admin.assistant.account.AssistantAccounts;
import top.continew.admin.assistant.account.AssistantApiException;
import top.continew.admin.assistant.eligibility.AssistantEligibility.Consent;
import top.continew.admin.assistant.eligibility.AssistantEligibility.Consents;
import top.continew.admin.assistant.eligibility.AssistantEligibility.Status;
import top.continew.starter.extension.tenant.annotation.TenantIgnore;
import top.continew.starter.log.annotation.Log;

/**
 * Shows bounded synthetic eligibility status and receives current guardian decisions.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
@RestController
@RequestMapping("/api/assistant/v1/eligibility")
@ConditionalOnProperty(name = "assistant.enabled", havingValue = "true")
@ExcludeFromGracefulResponse
@SaIgnore
@TenantIgnore
@Log(ignore = true)
public class AssistantEligibilityController {

    private static final String WORKSPACE = "X-Workspace-Id";
    private final AssistantAccounts accounts;
    private final AssistantEligibility eligibility;

    /**
     * Creates the pilot eligibility API.
     *
     * @param accounts current authenticated consumer
     * @param eligibility scoped consent service
     */
    public AssistantEligibilityController(AssistantAccounts accounts,
        AssistantEligibility eligibility) {
        this.accounts = accounts;
        this.eligibility = eligibility;
    }

    /**
     * Returns current connector eligibility for the authenticated account.
     *
     * @param authorization live consumer token
     * @param workspace currently selected workspace
     * @return safe eligibility and reason
     */
    @GetMapping
    public ResponseEntity<Status> status(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace) {
        var result = eligibility.status(accounts.authenticate(authorization, workspace));
        accounts.authenticate(authorization, workspace);
        return respond(HttpStatus.OK, result);
    }

    /**
     * Lists current opaque consent records assigned to the guardian.
     *
     * @param authorization live guardian token
     * @param workspace selected fixture workspace
     * @return current scoped consent records
     */
    @GetMapping("/guardian/consents")
    public ResponseEntity<Consents> list(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace) {
        var result = eligibility.guardian(accounts.authenticate(authorization, workspace));
        accounts.authenticate(authorization, workspace);
        return respond(HttpStatus.OK, result);
    }

    /**
     * Saves a guardian's confirmed decision at the reviewed consent revision.
     *
     * @param authorization live guardian token
     * @param workspace selected fixture workspace
     * @param id opaque existing enrollment ID
     * @param body exact revision and consent decision
     * @return current consent receipt
     */
    @PutMapping("/guardian/consents/{id}")
    public ResponseEntity<Consent> decide(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace,
        @PathVariable("id") String id, @RequestBody(required = false) JsonNode body) {
        var actor = accounts.authenticate(authorization, workspace);
        if (body == null || !body.isObject() || body.size() != 3
            || !body.path("revision").isTextual() || !body.path("authorize").isBoolean()
            || !body.path("confirm").isBoolean()) {
            throw invalid();
        }
        return respond(HttpStatus.OK,
            eligibility.decide(actor, id, body.path("revision").textValue(),
                body.path("authorize").booleanValue(), body.path("confirm").booleanValue()));
    }

    /**
     * Returns a controlled, non-cacheable consent error.
     *
     * @param failure controlled consumer failure
     * @return stable error response
     */
    @ExceptionHandler(AssistantApiException.class)
    public ResponseEntity<ApiError> handle(AssistantApiException failure) {
        return respond(failure.status(), new ApiError(failure.code(), failure.getMessage()));
    }

    /**
     * Maps malformed JSON to the validation response.
     *
     * @return safe error response
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> malformed() {
        return handle(invalid());
    }

    /**
     * Conceals internal persistence details.
     *
     * @return bounded operational error
     */
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ApiError> storage() {
        return handle(new AssistantApiException(HttpStatus.SERVICE_UNAVAILABLE,
            "CAPABILITY_UNAVAILABLE", "Synthetic eligibility controls are unavailable."));
    }

    private static AssistantApiException invalid() {
        return new AssistantApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
            "Confirm an exact current consent revision.");
    }

    private static <T> ResponseEntity<T> respond(HttpStatus status, T value) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
            .cacheControl(CacheControl.noStore()).body(value);
    }
}

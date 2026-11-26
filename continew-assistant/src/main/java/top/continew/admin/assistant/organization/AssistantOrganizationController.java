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
import com.feiniaojin.gracefulresponse.api.ExcludeFromGracefulResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
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
import top.continew.admin.assistant.organization.AssistantOrganizations.AuditEntry;
import top.continew.admin.assistant.organization.AssistantOrganizations.Member;
import top.continew.admin.assistant.organization.AssistantOrganizations.Page;
import top.continew.admin.assistant.organization.AssistantOrganizations.Summary;
import top.continew.admin.assistant.organization.AssistantOrganizations.Usage;
import top.continew.starter.extension.tenant.annotation.TenantIgnore;
import top.continew.starter.log.annotation.Log;

/**
 * Exposes consumer organization operations for the selected workspace only.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
@RestController
@RequestMapping("/api/assistant/v1/organizations/current")
@ConditionalOnProperty(name = "assistant.enabled", havingValue = "true")
@ExcludeFromGracefulResponse
@SaIgnore
@TenantIgnore
@Log(ignore = true)
public class AssistantOrganizationController {

    private static final String WORKSPACE = "X-Workspace-Id";
    private final AssistantAccounts accounts;
    private final AssistantOrganizations organizations;

    /**
     * Connects consumer device authentication to the organization manager.
     *
     * @param accounts consumer bearer and workspace authorization
     * @param organizations local organization governance
     */
    public AssistantOrganizationController(AssistantAccounts accounts,
        AssistantOrganizations organizations) {
        this.accounts = accounts;
        this.organizations = organizations;
    }

    /**
     * Returns the organization's current safe summary for an enrolled consumer.
     *
     * @param authorization consumer bearer token
     * @param workspace selected workspace
     * @return organization and local seat counts
     */
    @GetMapping
    public ResponseEntity<Summary> current(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace) {
        var result = organizations.current(accounts.authenticate(authorization, workspace));
        accounts.authenticate(authorization, workspace);
        return respond(HttpStatus.OK, result);
    }

    /**
     * Reads the roster as a current organization manager.
     *
     * @param authorization consumer bearer token
     * @param workspace selected workspace
     * @param cursor scoped continuation
     * @param limit maximum requested members
     * @return bounded roster
     */
    @GetMapping("/members")
    public ResponseEntity<Page<Member>> members(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace,
        @RequestParam(value = "cursor", required = false) String cursor,
        @RequestParam(value = "limit", required = false) Integer limit) {
        var result =
            organizations.members(accounts.authenticate(authorization, workspace), cursor, limit);
        accounts.authenticate(authorization, workspace);
        return respond(HttpStatus.OK, result);
    }

    /**
     * Enrolls a specifically selected existing account.
     *
     * @param authorization consumer bearer token
     * @param workspace selected workspace
     * @param body confirmed account and granted role
     * @return current member projection
     */
    @PostMapping("/members")
    public ResponseEntity<Member> enroll(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace,
        @RequestBody(required = false) JsonNode body) {
        var principal = accounts.authenticate(authorization, workspace);
        requireFields(body, 3);
        return respond(HttpStatus.CREATED, organizations.enroll(principal, text(body, "accountId"),
            text(body, "role"), decision(body)));
    }

    /**
     * Changes an exact reviewed member role as the organization owner.
     *
     * @param authorization consumer bearer token
     * @param workspace selected workspace
     * @param accountId reviewed member identifier
     * @param body both current generations and the affirmative action
     * @return refreshed role projection
     */
    @PutMapping("/members/{accountId}/role")
    public ResponseEntity<Member> role(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace,
        @PathVariable("accountId") String accountId, @RequestBody(required = false) JsonNode body) {
        var principal = accounts.authenticate(authorization, workspace);
        requireFields(body, 4);
        return respond(HttpStatus.OK, organizations.changeRole(principal, accountId,
            text(body, "membershipRevision"), text(body, "authorityRevision"),
            text(body, "role"), decision(body)));
    }

    /**
     * Revokes an exact organization enrollment under current role authority.
     *
     * @param authorization consumer bearer token
     * @param workspace selected workspace
     * @param accountId reviewed member identifier
     * @param body exact membership generation and confirmation
     * @return completed removal
     */
    @PostMapping("/members/{accountId}/revoke")
    public ResponseEntity<Void> revoke(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace,
        @PathVariable("accountId") String accountId, @RequestBody(required = false) JsonNode body) {
        var principal = accounts.authenticate(authorization, workspace);
        requireFields(body, 2);
        organizations.revoke(principal, accountId, text(body, "membershipRevision"),
            decision(body));
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    /**
     * Reads the selected organization's recent governance audit.
     *
     * @param authorization consumer bearer token
     * @param workspace selected workspace
     * @param cursor scoped continuation
     * @param limit bounded page size
     * @return recent action receipts visible under the current role
     */
    @GetMapping("/audit")
    public ResponseEntity<Page<AuditEntry>> audit(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace,
        @RequestParam(value = "cursor", required = false) String cursor,
        @RequestParam(value = "limit", required = false) Integer limit) {
        var result =
            organizations.audit(accounts.authenticate(authorization, workspace), cursor, limit);
        accounts.authenticate(authorization, workspace);
        return respond(HttpStatus.OK, result);
    }

    /**
     * Returns UTC ledger totals to current organization owners and administrators.
     *
     * @param authorization consumer bearer token
     * @param workspace selected workspace
     * @param days bounded report duration including today
     * @return organization-only counts and units by submission date
     */
    @GetMapping("/usage")
    public ResponseEntity<Usage> usage(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace,
        @RequestParam(value = "days", required = false) Integer days) {
        var result = organizations.usage(accounts.authenticate(authorization, workspace), days);
        accounts.authenticate(authorization, workspace);
        return respond(HttpStatus.OK, result);
    }

    /**
     * Maps typed organization errors without cache or private details.
     *
     * @param failure authorized public error
     * @return no-store error
     */
    @ExceptionHandler(AssistantApiException.class)
    public ResponseEntity<ApiError> handle(AssistantApiException failure) {
        return respond(failure.status(), new ApiError(failure.code(), failure.getMessage()));
    }

    /**
     * Maps malformed input to its stable organization error.
     *
     * @return valid error envelope
     */
    @ExceptionHandler({HttpMessageNotReadableException.class,
        MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiError> malformed() {
        return handle(invalid());
    }

    /**
     * Conceals data-store errors on protected operations.
     *
     * @return temporary service error
     */
    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    public ResponseEntity<ApiError> storage() {
        return handle(
            new AssistantApiException(HttpStatus.SERVICE_UNAVAILABLE, "CAPABILITY_UNAVAILABLE",
                "Organization management is temporarily unavailable."));
    }

    private static void requireFields(JsonNode body, int count) {
        if (body == null || !body.isObject() || body.size() != count) {
            throw invalid();
        }
    }

    private static String text(JsonNode body, String field) {
        if (!body.path(field).isTextual()) {
            throw invalid();
        }
        return body.path(field).textValue();
    }

    private static boolean decision(JsonNode body) {
        if (!body.path("confirm").isBoolean()) {
            throw invalid();
        }
        return body.path("confirm").booleanValue();
    }

    private static AssistantApiException invalid() {
        return new AssistantApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
            "The organization request is invalid.");
    }

    private static <T> ResponseEntity<T> respond(HttpStatus status, T body) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
            .cacheControl(CacheControl.noStore()).body(body);
    }
}

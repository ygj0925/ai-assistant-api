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
import com.fasterxml.jackson.databind.DeserializationFeature;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import top.continew.admin.assistant.account.AssistantAccountController.ApiError;
import top.continew.admin.assistant.account.AssistantAccounts;
import top.continew.admin.assistant.account.AssistantApiException;
import top.continew.admin.assistant.organization.AssistantOrganizationModelBudgets.View;
import top.continew.starter.extension.tenant.annotation.TenantIgnore;
import top.continew.starter.log.annotation.Log;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;

/**
 * Exposes the current organization's reviewed model-unit cap to its owner.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
@RestController
@RequestMapping("/api/assistant/v1/organizations/current/model-budget")
@ConditionalOnProperty(name = "assistant.enabled", havingValue = "true")
@ExcludeFromGracefulResponse
@SaIgnore
@TenantIgnore
@Log(ignore = true)
public class AssistantOrganizationModelBudgetController {

    private static final String WORKSPACE = "X-Workspace-Id";
    private static final int MAX_BODY_BYTES = 512;
    private final AssistantAccounts accounts;
    private final AssistantOrganizationModelBudgets budgets;
    private final ObjectReader reader;

    /**
     * Creates the scoped budget management endpoint.
     *
     * @param accounts consumer device authentication
     * @param budgets current organization model budget operations
     * @param mapper strictly reviews the exact requested JSON body
     */
    public AssistantOrganizationModelBudgetController(AssistantAccounts accounts,
        AssistantOrganizationModelBudgets budgets, ObjectMapper mapper) {
        this.accounts = accounts;
        this.budgets = budgets;
        this.reader = mapper.reader().with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    /**
     * Returns the exact current budget and its UTC accounting window to the owner.
     *
     * @param authorization active consumer bearer credential
     * @param workspace selected organization workspace
     * @return current reviewed cap and balances
     */
    @GetMapping
    public ResponseEntity<View> read(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace) {
        var view = budgets.read(accounts.authenticate(authorization, workspace));
        accounts.authenticate(authorization, workspace);
        return respond(HttpStatus.OK, view);
    }

    /**
     * Applies a confirmed, exact owner revision with explicit integer or null cap.
     *
     * @param authorization active consumer bearer credential
     * @param workspace selected organization workspace
     * @param request bounded exact JSON decision
     * @return current reviewed cap and balances
     */
    @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<View> update(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace,
        HttpServletRequest request) {
        var actor = accounts.authenticate(authorization, workspace);
        var decision = parse(readBody(request));
        return respond(HttpStatus.OK,
            budgets.update(actor, decision.baseRevision(), decision.limit(), decision.confirm()));
    }

    private JsonNode readBody(HttpServletRequest request) {
        if (request.getContentLengthLong() > MAX_BODY_BYTES) {
            throw AssistantOrganizationModelBudgets.invalid();
        }
        try {
            byte[] bytes = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
            if (bytes.length > MAX_BODY_BYTES) {
                throw AssistantOrganizationModelBudgets.invalid();
            }
            return reader.readTree(bytes);
        } catch (IOException failure) {
            throw AssistantOrganizationModelBudgets.invalid();
        }
    }

    static Decision parse(JsonNode body) {
        if (body == null || !body.isObject() || body.size() != 3
            || !body.path("baseRevision").isTextual() || !body.path("confirm").isBoolean()
            || !body.has("monthlyLimitUnits")) {
            throw AssistantOrganizationModelBudgets.invalid();
        }
        JsonNode cap = body.get("monthlyLimitUnits");
        if (!cap.isNull() && (!cap.isIntegralNumber() || !cap.canConvertToLong())) {
            throw AssistantOrganizationModelBudgets.invalid();
        }
        return new Decision(body.path("baseRevision").textValue(),
            cap.isNull() ? null : cap.longValue(), body.path("confirm").booleanValue());
    }

    /**
     * Returns a sanitized, noncacheable scope or budget error.
     *
     * @param failure typed expected failure
     * @return typed error and response status
     */
    @ExceptionHandler(AssistantApiException.class)
    public ResponseEntity<ApiError> handle(AssistantApiException failure) {
        return respond(failure.status(), new ApiError(failure.code(), failure.getMessage()));
    }

    /**
     * Denies budget mutations when persisted state cannot be checked.
     *
     * @return temporary budget availability error
     */
    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    public ResponseEntity<ApiError> storageFailure() {
        return handle(OrganizationBudgetGuard.recoveryRequired());
    }

    private static <T> ResponseEntity<T> respond(HttpStatus status, T result) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
            .cacheControl(CacheControl.noStore()).body(result);
    }

    record Decision(String baseRevision, Long limit, boolean confirm) {
    }
}

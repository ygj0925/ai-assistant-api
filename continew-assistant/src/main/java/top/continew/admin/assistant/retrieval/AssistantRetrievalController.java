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

package top.continew.admin.assistant.retrieval;

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
import top.continew.admin.assistant.retrieval.AssistantRetrieval.Answer;
import top.continew.starter.extension.tenant.annotation.TenantIgnore;
import top.continew.starter.log.annotation.Log;

import java.util.Optional;

/**
 * Authenticated consumer endpoints for evidence retrieval.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
@RestController
@RequestMapping("/api/assistant/v1/retrieval/answers")
@ConditionalOnProperty(name = "assistant.enabled", havingValue = "true")
@ExcludeFromGracefulResponse
@SaIgnore
@TenantIgnore
@Log(ignore = true)
public class AssistantRetrievalController {

    private static final String WORKSPACE = "X-Workspace-Id";
    private final AssistantAccounts accounts;
    private final Optional<AssistantRetrieval> retrieval;

    /**
     * Connects the retrieval route to consumer authentication.
     *
     * @param accounts consumer sessions
     * @param retrieval optional catalog service
     */
    public AssistantRetrievalController(AssistantAccounts accounts,
        Optional<AssistantRetrieval> retrieval) {
        this.accounts = accounts;
        this.retrieval = retrieval;
    }

    /**
     * Retrieves matching evidence and persists a private answer.
     *
     * @param authorization consumer bearer token
     * @param workspace selected workspace
     * @param request single text query
     * @return supported, partially supported or no-source answer
     */
    @PostMapping
    public ResponseEntity<Answer> create(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace,
        @RequestBody(required = false) JsonNode request) {
        var user = accounts.authenticate(authorization, workspace).user();
        AssistantRetrieval service = available();
        if (request == null || !request.isObject() || request.size() != 1
            || !request.path("query").isTextual()) {
            throw invalid();
        }
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
            .body(service.create(user, request.path("query").textValue()));
    }

    /**
     * Returns current citations for a stored answer owned by the caller.
     *
     * @param authorization consumer bearer token
     * @param workspace selected workspace
     * @param id answer identifier
     * @return the private answer
     */
    @GetMapping("/{answerId}")
    public ResponseEntity<Answer> get(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace,
        @PathVariable("answerId") String id) {
        var user = accounts.authenticate(authorization, workspace).user();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(available().get(user, id));
    }

    /**
     * Returns a sanitized consumer error.
     *
     * @param failure consumer-visible failure
     * @return public error envelope
     */
    @ExceptionHandler(AssistantApiException.class)
    public ResponseEntity<ApiError> error(AssistantApiException failure) {
        return ResponseEntity.status(failure.status()).cacheControl(CacheControl.noStore())
            .body(new ApiError(failure.code(), failure.getMessage()));
    }

    /**
     * Rejects a malformed JSON request.
     *
     * @return sanitized validation error
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> invalidInput() {
        return error(invalid());
    }

    /**
     * Omits internal storage details.
     *
     * @return retryable retrieval service error
     */
    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    public ResponseEntity<ApiError> unavailable() {
        return error(new AssistantApiException(HttpStatus.SERVICE_UNAVAILABLE,
            "PROVIDER_UNAVAILABLE", "Evidence retrieval is temporarily unavailable."));
    }

    private AssistantRetrieval available() {
        return retrieval.orElseThrow(() -> new AssistantApiException(HttpStatus.FORBIDDEN,
            "CAPABILITY_UNAVAILABLE", "Evidence retrieval is disabled."));
    }

    private static AssistantApiException invalid() {
        return new AssistantApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
            "A search query is required.");
    }
}

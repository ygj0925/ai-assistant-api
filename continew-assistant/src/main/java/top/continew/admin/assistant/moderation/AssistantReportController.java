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

package top.continew.admin.assistant.moderation;

import cn.dev33.satoken.annotation.SaIgnore;
import com.feiniaojin.gracefulresponse.api.ExcludeFromGracefulResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import top.continew.admin.assistant.account.AssistantAccountController.ApiError;
import top.continew.admin.assistant.account.AssistantAccounts;
import top.continew.admin.assistant.account.AssistantApiException;
import top.continew.admin.assistant.moderation.AssistantReports.Receipt;
import top.continew.admin.assistant.moderation.AssistantReports.ReportRequest;
import top.continew.starter.extension.tenant.annotation.TenantIgnore;
import top.continew.starter.log.annotation.Log;

/**
 * Receives reports from authenticated assistant consumers.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
@RestController
@RequestMapping("/api/assistant/v1/reports")
@ConditionalOnProperty(name = "assistant.enabled", havingValue = "true")
@ExcludeFromGracefulResponse
@SaIgnore
@TenantIgnore
@Log(ignore = true)
public class AssistantReportController {

    private final AssistantAccounts accounts;
    private final AssistantReports reports;

    /**
     * Creates the report endpoint.
     *
     * @param accounts consumer sessions and membership checks
     * @param reports report service
     */
    public AssistantReportController(AssistantAccounts accounts, AssistantReports reports) {
        this.accounts = accounts;
        this.reports = reports;
    }

    /**
     * Reports a visible object in the caller's workspace.
     *
     * @param authorization consumer bearer token
     * @param workspace requested workspace
     * @param request target and reason
     * @return created or repeated report receipt
     */
    @PostMapping
    public ResponseEntity<Receipt> submit(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = "X-Workspace-Id", required = false) String workspace,
        @RequestBody(required = false) ReportRequest request) {
        var submission = reports.submit(accounts.authenticate(authorization, workspace), request);
        return ResponseEntity.status(submission.created() ? HttpStatus.CREATED : HttpStatus.OK)
            .cacheControl(CacheControl.noStore()).body(submission.receipt());
    }

    /**
     * Returns the consumer error envelope.
     *
     * @param error public API failure
     * @return sanitized failure
     */
    @ExceptionHandler(AssistantApiException.class)
    public ResponseEntity<ApiError> handle(AssistantApiException error) {
        return ResponseEntity.status(error.status()).cacheControl(CacheControl.noStore())
            .body(new ApiError(error.code(), error.getMessage()));
    }

    /**
     * Handles malformed input.
     *
     * @return validation error
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> malformed() {
        return handle(new AssistantApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
            "An object containing the report target and reason is required."));
    }

    /**
     * Handles storage failures.
     *
     * @return sanitized service error
     */
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ApiError> storage() {
        return handle(new AssistantApiException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
            "Reports are temporarily unavailable."));
    }
}

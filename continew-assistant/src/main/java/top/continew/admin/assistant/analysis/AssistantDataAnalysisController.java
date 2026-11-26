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

package top.continew.admin.assistant.analysis;

import cn.dev33.satoken.annotation.SaIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import com.feiniaojin.gracefulresponse.api.ExcludeFromGracefulResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import top.continew.admin.assistant.account.AssistantAccountController.ApiError;
import top.continew.admin.assistant.account.AssistantAccounts;
import top.continew.admin.assistant.account.AssistantApiException;
import top.continew.admin.assistant.analysis.AssistantDataAnalysis.Summary;
import top.continew.starter.extension.tenant.annotation.TenantIgnore;
import top.continew.starter.log.annotation.Log;

import java.nio.charset.StandardCharsets;

/**
 * Authenticated numeric summaries and private downloads for project CSV materials.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
@RestController
@RequestMapping("/api/assistant/v1")
@ConditionalOnProperty(name = "assistant.enabled", havingValue = "true")
@ExcludeFromGracefulResponse
@SaIgnore
@TenantIgnore
@Log(ignore = true)
public class AssistantDataAnalysisController {

    private static final String WORKSPACE_HEADER = "X-Workspace-Id";
    private static final MediaType CSV = new MediaType("text", "csv", StandardCharsets.UTF_8);
    private static final String DOWNLOAD = ContentDisposition.attachment()
        .filename("analysis.csv").build().toString();

    private final AssistantAccounts accounts;
    private final AssistantDataAnalysis analysis;

    /**
     * Connects private report endpoints to consumer authentication.
     *
     * @param accounts consumer authentication
     * @param analysis built-in numerical analysis
     */
    public AssistantDataAnalysisController(AssistantAccounts accounts,
        AssistantDataAnalysis analysis) {
        this.accounts = accounts;
        this.analysis = analysis;
    }

    /**
     * Computes a numerical report from a private project material.
     *
     * @param authorization consumer bearer token
     * @param workspace selected workspace
     * @param projectId owned project
     * @param resourceId owned material
     * @param body JSON object containing the valueColumn text field
     * @return stored report and chart sample
     */
    @PostMapping("/projects/{projectId}/resources/{resourceId}/analysis")
    public ResponseEntity<Summary> create(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE_HEADER, required = false) String workspace,
        @PathVariable("projectId") String projectId,
        @PathVariable("resourceId") String resourceId,
        @RequestBody(required = false) JsonNode body) {
        var owner = accounts.authenticate(authorization, workspace).user();
        if (body == null || !body.isObject() || body.size() != 1
            || !body.path("valueColumn").isTextual()) {
            throw new AssistantApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                "Analysis requires one valueColumn text field.");
        }
        return response(HttpStatus.CREATED, analysis.create(owner, projectId, resourceId,
            body.get("valueColumn").textValue()));
    }

    /**
     * Returns a previously computed report.
     *
     * @param authorization consumer bearer token
     * @param workspace selected workspace
     * @param analysisId stored report identifier
     * @return visible report
     */
    @GetMapping("/analysis/{analysisId}")
    public ResponseEntity<Summary> get(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE_HEADER, required = false) String workspace,
        @PathVariable("analysisId") String analysisId) {
        var owner = accounts.authenticate(authorization, workspace).user();
        return response(HttpStatus.OK, analysis.get(owner, analysisId));
    }

    /**
     * Downloads a safe numeric-only CSV artifact.
     *
     * @param authorization consumer bearer token
     * @param workspace selected workspace
     * @param analysisId stored report identifier
     * @return private CSV attachment
     */
    @GetMapping("/analysis/{analysisId}/artifact")
    public ResponseEntity<byte[]> artifact(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE_HEADER, required = false) String workspace,
        @PathVariable("analysisId") String analysisId) {
        var owner = accounts.authenticate(authorization, workspace).user();
        byte[] attachment = analysis.artifact(owner, analysisId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(CSV)
            .header(HttpHeaders.CONTENT_DISPOSITION, DOWNLOAD)
            .header("X-Content-Type-Options", "nosniff").body(attachment);
    }

    /**
     * Returns a typed analysis failure without caching.
     *
     * @param failure expected public failure
     * @return error envelope
     */
    @ExceptionHandler(AssistantApiException.class)
    public ResponseEntity<ApiError> handleApiError(AssistantApiException failure) {
        return response(failure.status(), new ApiError(failure.code(), failure.getMessage()));
    }

    /**
     * Rejects malformed input without echoing the request body.
     *
     * @return validation failure
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleInvalidRequest() {
        return response(HttpStatus.BAD_REQUEST,
            new ApiError("VALIDATION_ERROR", "Invalid analysis request."));
    }

    /**
     * Conceals persistence details from clients.
     *
     * @return temporary availability failure
     */
    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    public ResponseEntity<ApiError> handleStorageFailure() {
        return response(HttpStatus.SERVICE_UNAVAILABLE,
            new ApiError("INTERNAL_ERROR", "Data analysis is temporarily unavailable."));
    }

    private static <T> ResponseEntity<T> response(HttpStatus status, T body) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
            .contentType(MediaType.APPLICATION_JSON).body(body);
    }
}

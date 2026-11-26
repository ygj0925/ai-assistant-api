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

package top.continew.admin.assistant.catalog;

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
import top.continew.admin.assistant.catalog.AssistantCatalog.CatalogRelease;
import top.continew.admin.assistant.catalog.AssistantCatalog.Item;
import top.continew.admin.assistant.catalog.AssistantCatalog.Page;
import top.continew.admin.assistant.catalog.AssistantCatalog.Preview;
import top.continew.admin.assistant.catalog.AssistantCatalog.Report;
import top.continew.admin.assistant.catalog.AssistantCatalog.SourceRequest;
import top.continew.starter.extension.tenant.annotation.TenantIgnore;
import top.continew.starter.log.annotation.Log;

import java.util.Set;

/**
 * Provides authenticated discovery and explicit author submissions for the reviewed catalogue.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
@RestController
@RequestMapping("/api/assistant/v1/catalog")
@ConditionalOnProperty(name = "assistant.enabled", havingValue = "true")
@ExcludeFromGracefulResponse
@SaIgnore
@TenantIgnore
@Log(ignore = true)
public class AssistantCatalogController {

    private static final String WORKSPACE = "X-Workspace-Id";
    private static final Set<String> SOURCE_FIELDS = Set.of("kind", "assistantId", "versionId",
        "summary");
    private static final Set<String> SUBMISSION_FIELDS = Set.of("kind", "assistantId", "versionId",
        "summary", "contentDigest", "confirm");
    private final AssistantAccounts accounts;
    private final AssistantCatalog catalog;

    /**
     * Creates scoped catalogue endpoints.
     *
     * @param accounts consumer session authentication
     * @param catalog reviewed release service
     */
    public AssistantCatalogController(AssistantAccounts accounts, AssistantCatalog catalog) {
        this.accounts = accounts;
        this.catalog = catalog;
    }

    /**
     * Resolves the exact source material a live author is considering.
     *
     * @param authorization live consumer token
     * @param workspace selected owner workspace
     * @param body exact source selector
     * @return non-cacheable disclosure preview
     */
    @PostMapping("/author/preview")
    public ResponseEntity<Preview> preview(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace,
        @RequestBody(required = false) JsonNode body) {
        var actor = accounts.authenticate(authorization, workspace);
        var value = catalog.preview(actor, source(body, SOURCE_FIELDS));
        accounts.authenticate(authorization, workspace);
        return respond(HttpStatus.OK, value);
    }

    /**
     * Queues an exact preview for independent operator review.
     *
     * @param authorization live consumer token
     * @param workspace current author workspace
     * @param key idempotency key
     * @param body confirmed exact preview
     * @return pending submission receipt
     */
    @PostMapping("/author/releases")
    public ResponseEntity<CatalogRelease> submit(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace,
        @RequestHeader(value = "Idempotency-Key", required = false) String key,
        @RequestBody(required = false) JsonNode body) {
        var actor = accounts.authenticate(authorization, workspace);
        return respond(HttpStatus.CREATED, catalog.submit(actor, source(body, SUBMISSION_FIELDS),
            requiredText(body, "contentDigest"), confirmed(body), key));
    }

    /**
     * Lists the current workspace author's release receipts.
     *
     * @param authorization live consumer token
     * @param workspace current author workspace
     * @param limit bounded page size
     * @param cursor continuation token
     * @return only releases authored by the current account in the selected workspace
     */
    @GetMapping("/author/releases")
    public ResponseEntity<Page<CatalogRelease>> authored(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace,
        @RequestParam(value = "limit", required = false) Integer limit,
        @RequestParam(value = "cursor", required = false) String cursor) {
        var result =
            catalog.authored(accounts.authenticate(authorization, workspace), limit, cursor);
        accounts.authenticate(authorization, workspace);
        return respond(HttpStatus.OK, result);
    }

    /**
     * Withdraws one authored release promptly.
     *
     * @param authorization live consumer token
     * @param workspace current author workspace
     * @param releaseId exact existing author release
     * @param body explicit confirmation
     * @return current author-side release receipt
     */
    @PostMapping("/author/releases/{releaseId}/withdraw")
    public ResponseEntity<CatalogRelease> withdraw(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace,
        @PathVariable("releaseId") String releaseId, @RequestBody(required = false) JsonNode body) {
        var actor = accounts.authenticate(authorization, workspace);
        fields(body, Set.of("confirm"));
        return respond(HttpStatus.OK, catalog.withdraw(actor, releaseId, confirmed(body)));
    }

    /**
     * Lists active reviewed entries available to signed-in consumers.
     *
     * @param authorization live consumer token
     * @param workspace selected viewer workspace
     * @param kind optional entry kind filter
     * @param limit bounded page size
     * @param cursor continuation token
     * @return safe public projections without instruction bodies
     */
    @GetMapping("/items")
    public ResponseEntity<Page<Item>> items(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace,
        @RequestParam(value = "kind", required = false) String kind,
        @RequestParam(value = "limit", required = false) Integer limit,
        @RequestParam(value = "cursor", required = false) String cursor) {
        accounts.authenticate(authorization, workspace);
        var result = catalog.items(kind, limit, cursor);
        accounts.authenticate(authorization, workspace);
        return respond(HttpStatus.OK, result);
    }

    /**
     * Reads one current still-eligible approved entry.
     *
     * @param authorization live consumer token
     * @param workspace selected viewer workspace
     * @param entryId stable catalogue entry identifier
     * @return expressly published fields and source text if applicable
     */
    @GetMapping("/items/{entryId}")
    public ResponseEntity<Item> item(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace,
        @PathVariable("entryId") String entryId) {
        accounts.authenticate(authorization, workspace);
        var result = catalog.item(entryId);
        accounts.authenticate(authorization, workspace);
        return respond(HttpStatus.OK, result);
    }

    /**
     * Reports only the exact current approved release the viewer inspected.
     *
     * @param authorization live consumer token
     * @param workspace selected viewer workspace
     * @param entryId stable catalogue entry identifier
     * @param body exact release and constrained reason
     * @return new or already accepted report receipt
     */
    @PostMapping("/items/{entryId}/reports")
    public ResponseEntity<Report> report(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace,
        @PathVariable("entryId") String entryId, @RequestBody(required = false) JsonNode body) {
        var actor = accounts.authenticate(authorization, workspace);
        fields(body, Set.of("releaseId", "reason"));
        var result = catalog.report(actor, entryId, requiredText(body, "releaseId"),
            requiredText(body, "reason"));
        return respond(result.created() ? HttpStatus.CREATED : HttpStatus.OK, result.receipt());
    }

    /**
     * Returns scrubbed catalogue errors with explicit JSON and cache policy.
     *
     * @param error controlled consumer failure
     * @return stable consumer error
     */
    @ExceptionHandler(AssistantApiException.class)
    public ResponseEntity<ApiError> handle(AssistantApiException error) {
        return respond(error.status(), new ApiError(error.code(), error.getMessage()));
    }

    /**
     * Maps malformed parameters and JSON to validation errors.
     *
     * @return stable consumer error
     */
    @ExceptionHandler({HttpMessageNotReadableException.class,
        MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiError> malformed() {
        return handle(invalid());
    }

    /**
     * Conceals database and storage details.
     *
     * @return stable operational error
     */
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ApiError> storage() {
        return handle(new AssistantApiException(HttpStatus.SERVICE_UNAVAILABLE,
            "CAPABILITY_UNAVAILABLE", "The reviewed catalogue is temporarily unavailable."));
    }

    private static SourceRequest source(JsonNode body, Set<String> allowed) {
        fields(body, allowed);
        return new SourceRequest(requiredText(body, "kind"), optionalText(body, "assistantId"),
            optionalText(body, "versionId"), optionalText(body, "summary"));
    }

    private static void fields(JsonNode body, Set<String> allowed) {
        if (body == null || !body.isObject() || body.size() > allowed.size()) {
            throw invalid();
        }
        var names = body.fieldNames();
        while (names.hasNext()) {
            if (!allowed.contains(names.next())) {
                throw invalid();
            }
        }
    }

    private static boolean confirmed(JsonNode body) {
        if (!body.path("confirm").isBoolean()) {
            throw invalid();
        }
        return body.path("confirm").booleanValue();
    }

    private static String requiredText(JsonNode body, String attribute) {
        String value = optionalText(body, attribute);
        if (value == null) {
            throw invalid();
        }
        return value;
    }

    private static String optionalText(JsonNode body, String attribute) {
        if (!body.has(attribute)) {
            return null;
        }
        if (!body.path(attribute).isTextual()) {
            throw invalid();
        }
        return body.path(attribute).textValue();
    }

    private static AssistantApiException invalid() {
        return new AssistantApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
            "Choose an eligible exact release and review the requested action.");
    }

    private static <T> ResponseEntity<T> respond(HttpStatus status, T body) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
            .cacheControl(CacheControl.noStore()).body(body);
    }
}

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

package top.continew.admin.assistant.commerce;

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
import top.continew.admin.assistant.commerce.AssistantSyntheticCatalogue.Comparison;
import top.continew.admin.assistant.commerce.AssistantSyntheticCatalogue.Page;
import top.continew.admin.assistant.commerce.AssistantSyntheticCatalogue.Product;
import top.continew.admin.assistant.commerce.AssistantSyntheticCatalogue.ProductSource;
import top.continew.starter.extension.tenant.annotation.TenantIgnore;
import top.continew.starter.log.annotation.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * Authenticated read-only routes for synthetic catalogue comparison and provenance.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
@RestController
@RequestMapping("/api/assistant/v1/commerce/catalogue")
@ConditionalOnProperty(name = "assistant.enabled", havingValue = "true")
@ExcludeFromGracefulResponse
@SaIgnore
@TenantIgnore
@Log(ignore = true)
public class AssistantSyntheticCatalogueController {

    private static final String WORKSPACE = "X-Workspace-Id";

    private final AssistantAccounts accounts;
    private final AssistantSyntheticCatalogue catalogue;

    /**
     * Connects current session authorization to the local fixture catalogue.
     *
     * @param accounts authenticated consumer service
     * @param catalogue approved synthetic catalogue
     */
    public AssistantSyntheticCatalogueController(AssistantAccounts accounts,
        AssistantSyntheticCatalogue catalogue) {
        this.accounts = accounts;
        this.catalogue = catalogue;
    }

    /**
     * Finds currently sourced products by a bounded literal query.
     *
     * @param authorization consumer credentials
     * @param workspace selected workspace
     * @param query optional plain query
     * @param limit maximum page size
     * @param cursor continuation
     * @return current synthetic catalogue page
     */
    @GetMapping("/products")
    public ResponseEntity<Page> search(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace,
        @RequestParam(value = "q", required = false) String query,
        @RequestParam(value = "limit", defaultValue = "24") int limit,
        @RequestParam(value = "cursor", required = false) String cursor) {
        accounts.authenticate(authorization, workspace);
        return response(HttpStatus.OK, catalogue.search(query, limit, cursor));
    }

    /**
     * Reads a selected product's authoritative current observation.
     *
     * @param authorization consumer credentials
     * @param workspace selected workspace
     * @param id stable product ID
     * @return product view
     */
    @GetMapping("/products/{id}")
    public ResponseEntity<Product> product(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace,
        @PathVariable("id") String id) {
        accounts.authenticate(authorization, workspace);
        return response(HttpStatus.OK, catalogue.get(id));
    }

    /**
     * Returns selected products against the same current price observation time.
     *
     * @param authorization consumer credentials
     * @param workspace selected workspace
     * @param body exact product selection
     * @return comparison in selection order
     */
    @PostMapping("/compare")
    public ResponseEntity<Comparison> compare(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace,
        @RequestBody JsonNode body) {
        accounts.authenticate(authorization, workspace);
        return response(HttpStatus.OK, catalogue.compare(selection(body)));
    }

    /**
     * Verifies the exact price provenance shown for a selected product.
     *
     * @param authorization consumer credentials
     * @param workspace selected workspace
     * @param id product ID
     * @param snapshotId approved snapshot ID
     * @return current approved source
     */
    @GetMapping("/products/{id}/source")
    public ResponseEntity<ProductSource> source(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace,
        @PathVariable("id") String id,
        @RequestParam(value = "snapshotId", required = false) String snapshotId) {
        accounts.authenticate(authorization, workspace);
        return response(HttpStatus.OK, catalogue.source(id, snapshotId));
    }

    /**
     * Maps catalogue failures to the consumer error format.
     *
     * @param error application failure
     * @return safe error
     */
    @ExceptionHandler(AssistantApiException.class)
    public ResponseEntity<ApiError> handle(AssistantApiException error) {
        return response(error.status(), new ApiError(error.code(), error.getMessage()));
    }

    /**
     * Rejects malformed comparison or query fields.
     *
     * @return validation error
     */
    @ExceptionHandler({HttpMessageNotReadableException.class,
        MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiError> malformed() {
        return handle(invalid());
    }

    /**
     * Returns a safe failure when catalogue storage is unavailable.
     *
     * @return storage error
     */
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ApiError> storage() {
        return response(HttpStatus.INTERNAL_SERVER_ERROR,
            new ApiError("INTERNAL_ERROR", "The synthetic catalogue is unavailable."));
    }

    private static List<String> selection(JsonNode body) {
        if (body == null || !body.isObject() || body.size() != 1) {
            throw invalid();
        }
        JsonNode ids = body.get("productIds");
        if (ids == null || !ids.isArray() || ids.size() < 2 || ids.size() > 4) {
            throw invalid();
        }
        List<String> selected = new ArrayList<>(ids.size());
        for (JsonNode id : ids) {
            if (!id.isTextual()) {
                throw invalid();
            }
            selected.add(id.textValue());
        }
        return selected;
    }

    private static AssistantApiException invalid() {
        return new AssistantApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
            "Choose two to four distinct catalogue product identifiers.");
    }

    private static <T> ResponseEntity<T> response(HttpStatus status, T payload) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(payload);
    }
}

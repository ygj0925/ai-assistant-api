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
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonParser;
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
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import top.continew.admin.assistant.account.AssistantApiException;
import top.continew.admin.assistant.organization.AssistantOrganizationDirectory.ScimUser;
import top.continew.starter.extension.tenant.annotation.TenantIgnore;
import top.continew.starter.log.annotation.Log;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Handles a bounded SCIM 2.0 lookup and deactivation flow with dedicated machine authentication.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
@RestController
@RequestMapping("/scim/v2/{directoryId}/Users")
@ConditionalOnProperty(name = "assistant.enabled", havingValue = "true")
@ExcludeFromGracefulResponse
@SaIgnore
@TenantIgnore
@Log(ignore = true)
class AssistantOrganizationScimController {

    private static final String SCIM = "application/scim+json";
    private static final String ERROR = "urn:ietf:params:scim:api:messages:2.0:Error";
    private static final Pattern LOOKUP = Pattern.compile("externalId eq \"([a-f0-9-]{36})\"");
    private static final Set<String> SCIM_BAD_REQUEST =
        Set.of("invalidValue", "invalidSyntax", "invalidFilter");
    private static final int MAX_PATCH_BYTES = 4096;
    private final AssistantOrganizationDirectory directory;
    private final ObjectReader reader;

    AssistantOrganizationScimController(AssistantOrganizationDirectory directory,
        ObjectMapper json) {
        this.directory = directory;
        this.reader = json.reader().with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    @GetMapping("/{id}")
    ResponseEntity<ScimUser> read(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @PathVariable String directoryId, @PathVariable String id) {
        return user(directory.read(authorization, directoryId, id));
    }

    @GetMapping
    ResponseEntity<Map<String, Object>> find(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @PathVariable String directoryId,
        @RequestParam(value = "filter", required = false) String filter,
        HttpServletRequest request) {
        directory.authenticate(authorization, directoryId);
        var lookup = LOOKUP.matcher(filter == null ? "" : filter);
        if (request.getParameterMap().size() != 1 || !lookup.matches()
            || !AssistantOrganizationDirectory.validId(lookup.group(1))) {
            throw new AssistantApiException(HttpStatus.BAD_REQUEST, "invalidFilter",
                "Supply one exact directory externalId filter.");
        }
        var users = directory.find(authorization, directoryId, lookup.group(1));
        return response(HttpStatus.OK)
            .body(Map.of("schemas", List.of(AssistantOrganizationDirectory.LIST_SCHEMA),
                "totalResults", users.size(), "itemsPerPage", users.size(), "startIndex", 1,
                "Resources", users));
    }

    @PatchMapping(path = "/{id}", consumes = SCIM)
    ResponseEntity<ScimUser> deactivate(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
        @PathVariable String directoryId, @PathVariable String id, HttpServletRequest request) {
        directory.authenticate(authorization, directoryId);
        validatePatch(request);
        return user(directory.deactivate(authorization, directoryId, id, ifMatch));
    }

    private void validatePatch(HttpServletRequest request) {
        try {
            byte[] input = request.getInputStream().readNBytes(MAX_PATCH_BYTES + 1);
            if (input.length == 0 || input.length > MAX_PATCH_BYTES) {
                throw invalidPatch();
            }
            JsonNode body = reader.readTree(input);
            if (body == null || !body.isObject() || body.size() != 2) {
                throw invalidPatch();
            }
            JsonNode schemas = body.path("schemas");
            JsonNode operations = body.path("Operations");
            JsonNode first = operations.path(0);
            if (!schemas.isArray() || schemas.size() != 1
                || !AssistantOrganizationDirectory.PATCH_SCHEMA.equals(schemas.path(0).textValue())
                || !operations.isArray() || operations.size() != 1 || !first.isObject()
                || first.size() != 3
                || !"replace".equalsIgnoreCase(first.path("op").textValue())
                || !"active".equalsIgnoreCase(first.path("path").textValue())
                || !first.path("value").isBoolean()
                || first.path("value").booleanValue()) {
                throw invalidPatch();
            }
        } catch (IOException failure) {
            throw invalidPatch();
        }
    }

    private static AssistantApiException invalidPatch() {
        return new AssistantApiException(HttpStatus.BAD_REQUEST, "invalidSyntax",
            "Supply one SCIM replace operation setting active to false.");
    }

    @ExceptionHandler(AssistantApiException.class)
    ResponseEntity<ScimError> reject(AssistantApiException failure) {
        String scimType = SCIM_BAD_REQUEST.contains(failure.code()) ? failure.code() : null;
        ResponseEntity.BodyBuilder result = response(failure.status());
        if (failure.status() == HttpStatus.UNAUTHORIZED) {
            result.header(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"assistant-directory\"");
        }
        return result.body(new ScimError(List.of(ERROR), Integer.toString(failure.status().value()),
            scimType, failure.getMessage()));
    }

    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    ResponseEntity<ScimError> unavailable() {
        return reject(new AssistantApiException(HttpStatus.SERVICE_UNAVAILABLE, "unavailable",
            "The organization directory is temporarily unavailable."));
    }

    private static ResponseEntity<ScimUser> user(ScimUser value) {
        return response(HttpStatus.OK).eTag(value.meta().version()).body(value);
    }

    private static ResponseEntity.BodyBuilder response(HttpStatus status) {
        return ResponseEntity.status(status).contentType(MediaType.parseMediaType(SCIM))
            .cacheControl(CacheControl.noStore());
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record ScimError(List<String> schemas, String status, String scimType, String detail) {
    }
}

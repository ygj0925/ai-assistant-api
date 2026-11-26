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
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import top.continew.admin.assistant.account.AssistantAccountController.ApiError;
import top.continew.admin.assistant.account.AssistantAccounts;
import top.continew.admin.assistant.account.AssistantApiException;
import top.continew.admin.assistant.organization.AssistantOrganizationDirectory.OwnerBinding;
import top.continew.starter.extension.tenant.annotation.TenantIgnore;
import top.continew.starter.log.annotation.Log;

/**
 * Registers an organization's explicitly reviewed consumer membership with its directory.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
@RestController
@RequestMapping("/api/assistant/v1/organizations/current/directory-bindings")
@ConditionalOnProperty(name = "assistant.enabled", havingValue = "true")
@ExcludeFromGracefulResponse
@SaIgnore
@TenantIgnore
@Log(ignore = true)
class AssistantOrganizationDirectoryController {

    private final AssistantAccounts accounts;
    private final AssistantOrganizationDirectory directory;

    AssistantOrganizationDirectoryController(AssistantAccounts accounts,
        AssistantOrganizationDirectory directory) {
        this.accounts = accounts;
        this.directory = directory;
    }

    @PostMapping
    ResponseEntity<OwnerBinding> bind(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = "X-Workspace-Id", required = false) String workspace,
        @RequestBody(required = false) JsonNode body) {
        var principal = accounts.authenticate(authorization, workspace);
        if (body == null || !body.isObject() || body.size() != 4
            || !body.path("confirm").isBoolean()) {
            throw new AssistantApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                "Review and confirm the exact member binding.");
        }
        OwnerBinding binding = directory.bind(principal, body.path("accountId").textValue(),
            body.path("membershipRevision").textValue(), body.path("externalId").textValue(),
            body.path("confirm").booleanValue());
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
            .body(binding);
    }

    @ExceptionHandler(AssistantApiException.class)
    ResponseEntity<ApiError> reject(AssistantApiException failure) {
        return ResponseEntity.status(failure.status()).cacheControl(CacheControl.noStore())
            .body(new ApiError(failure.code(), failure.getMessage()));
    }
}

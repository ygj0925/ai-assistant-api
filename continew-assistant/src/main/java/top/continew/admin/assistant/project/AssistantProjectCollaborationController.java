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

package top.continew.admin.assistant.project;

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
import org.springframework.web.bind.annotation.DeleteMapping;
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
import top.continew.admin.assistant.project.AssistantProjectCollaboration.Invitation;
import top.continew.admin.assistant.project.AssistantProjectCollaboration.Items;
import top.continew.admin.assistant.project.AssistantProjectCollaboration.Material;
import top.continew.admin.assistant.project.AssistantProjectCollaboration.MaterialSummary;
import top.continew.admin.assistant.project.AssistantProjectCollaboration.SharedProject;
import top.continew.starter.extension.tenant.annotation.TenantIgnore;
import top.continew.starter.log.annotation.Log;

/**
 * Exposes explicit project invitations and material collaboration.
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
public class AssistantProjectCollaborationController {

    private static final String WORKSPACE = "X-Workspace-Id";

    private final AssistantAccounts accounts;
    private final AssistantProjectCollaboration service;

    /**
     * Connects consumer authentication with current project collaboration scope.
     *
     * @param accounts consumer sessions
     * @param service collaboration roles and shared text
     */
    public AssistantProjectCollaborationController(AssistantAccounts accounts,
        AssistantProjectCollaboration service) {
        this.accounts = accounts;
        this.service = service;
    }

    /**
     * Invites an existing member of the selected workspace.
     *
     * @param authorization consumer credentials
     * @param workspace current workspace
     * @param id scoped identifier
     * @param input submitted data
     * @return non-cacheable collaboration response
     */
    @PostMapping("/projects/{id}/collaboration/invitations")
    public ResponseEntity<Invitation> invite(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace,
        @PathVariable("id") String id,
        @RequestBody(required = false) JsonNode input) {
        var actor = accounts.authenticate(authorization, workspace);
        requireFields(input, 2);
        return respond(HttpStatus.CREATED,
            service.invite(actor, id, field(input, "recipientUsername"), field(input, "role")));
    }

    /**
     * Lists the active invitations and roles of an owned project.
     *
     * @param authorization consumer credentials
     * @param workspace current workspace
     * @param id scoped identifier
     * @return non-cacheable collaboration response
     */
    @GetMapping("/projects/{id}/collaboration/invitations")
    public ResponseEntity<Items<Invitation>> ownerInvitations(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace,
        @PathVariable("id") String id) {
        var actor = accounts.authenticate(authorization, workspace);
        return respond(HttpStatus.OK, service.ownerInvitations(actor, id));
    }

    /**
     * Permanently retires an owned invitation or role.
     *
     * @param authorization consumer credentials
     * @param workspace current workspace
     * @param id scoped identifier
     * @param inviteId scoped identifier
     * @return non-cacheable collaboration response
     */
    @DeleteMapping("/projects/{id}/collaboration/invitations/{inviteId}")
    public ResponseEntity<Void> revoke(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace,
        @PathVariable("id") String id,
        @PathVariable("inviteId") String inviteId) {
        var actor = accounts.authenticate(authorization, workspace);
        service.revoke(actor, id, inviteId);
        return respond(HttpStatus.NO_CONTENT, null);
    }

    /**
     * Lists the recipient's pending invitations.
     *
     * @param authorization consumer credentials
     * @param workspace current workspace
     * @return non-cacheable collaboration response
     */
    @GetMapping("/collaboration/invitations")
    public ResponseEntity<Items<Invitation>> invitations(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace) {
        var actor = accounts.authenticate(authorization, workspace);
        return respond(HttpStatus.OK, service.invitations(actor));
    }

    /**
     * Confirms the authenticated recipient's invitation.
     *
     * @param authorization consumer credentials
     * @param workspace current workspace
     * @param id scoped identifier
     * @param input submitted data
     * @return non-cacheable collaboration response
     */
    @PostMapping("/collaboration/invitations/{id}/accept")
    public ResponseEntity<Invitation> accept(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace,
        @PathVariable("id") String id,
        @RequestBody(required = false) JsonNode input) {
        var actor = accounts.authenticate(authorization, workspace);
        requireFields(input, 1);
        JsonNode confirm = input.path("confirm");
        if (!confirm.isBoolean()) {
            throw invalid();
        }
        return respond(HttpStatus.OK, service.accept(actor, id, confirm.booleanValue()));
    }

    /**
     * Permanently declines an unaccepted invitation.
     *
     * @param authorization consumer credentials
     * @param workspace current workspace
     * @param id scoped identifier
     * @return non-cacheable collaboration response
     */
    @DeleteMapping("/collaboration/invitations/{id}")
    public ResponseEntity<Void> decline(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace,
        @PathVariable("id") String id) {
        var actor = accounts.authenticate(authorization, workspace);
        service.decline(actor, id);
        return respond(HttpStatus.NO_CONTENT, null);
    }

    /**
     * Lists accepted collaboration scopes.
     *
     * @param authorization consumer credentials
     * @param workspace current workspace
     * @return non-cacheable collaboration response
     */
    @GetMapping("/collaboration/projects")
    public ResponseEntity<Items<SharedProject>> sharedProjects(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace) {
        var actor = accounts.authenticate(authorization, workspace);
        return respond(HttpStatus.OK, service.sharedProjects(actor));
    }

    /**
     * Terminates the recipient's accepted role.
     *
     * @param authorization consumer credentials
     * @param workspace current workspace
     * @param id scoped identifier
     * @return non-cacheable collaboration response
     */
    @DeleteMapping("/collaboration/projects/{id}/membership")
    public ResponseEntity<Void> leave(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace,
        @PathVariable("id") String id) {
        var actor = accounts.authenticate(authorization, workspace);
        service.leave(actor, id);
        return respond(HttpStatus.NO_CONTENT, null);
    }

    /**
     * Lists only explicitly contributed material headings.
     *
     * @param authorization consumer credentials
     * @param workspace current workspace
     * @param id scoped identifier
     * @return non-cacheable collaboration response
     */
    @GetMapping("/collaboration/projects/{id}/materials")
    public ResponseEntity<Items<MaterialSummary>> listMaterials(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace,
        @PathVariable("id") String id) {
        var actor = accounts.authenticate(authorization, workspace);
        return respond(HttpStatus.OK, service.listMaterials(actor, id));
    }

    /**
     * Contributes explicit plain text with editor or owner authority.
     *
     * @param authorization consumer credentials
     * @param workspace current workspace
     * @param id scoped identifier
     * @param input submitted data
     * @return non-cacheable collaboration response
     */
    @PostMapping("/collaboration/projects/{id}/materials")
    public ResponseEntity<Material> createMaterial(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace,
        @PathVariable("id") String id,
        @RequestBody(required = false) JsonNode input) {
        var actor = accounts.authenticate(authorization, workspace);
        requireFields(input, 2);
        return respond(HttpStatus.CREATED,
            service.createMaterial(actor, id, field(input, "name"), field(input, "content")));
    }

    /**
     * Loads one shared text after rechecking current scope.
     *
     * @param authorization consumer credentials
     * @param workspace current workspace
     * @param id scoped identifier
     * @param materialId scoped identifier
     * @return non-cacheable collaboration response
     */
    @GetMapping("/collaboration/projects/{id}/materials/{materialId}")
    public ResponseEntity<Material> getMaterial(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace,
        @PathVariable("id") String id,
        @PathVariable("materialId") String materialId) {
        var actor = accounts.authenticate(authorization, workspace);
        return respond(HttpStatus.OK, service.getMaterial(actor, id, materialId));
    }

    /**
     * Durably removes shared text with authorized scope.
     *
     * @param authorization consumer credentials
     * @param workspace current workspace
     * @param id scoped identifier
     * @param materialId scoped identifier
     * @return non-cacheable collaboration response
     */
    @DeleteMapping("/collaboration/projects/{id}/materials/{materialId}")
    public ResponseEntity<Void> deleteMaterial(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace,
        @PathVariable("id") String id,
        @PathVariable("materialId") String materialId) {
        var actor = accounts.authenticate(authorization, workspace);
        service.deleteMaterial(actor, id, materialId);
        return respond(HttpStatus.NO_CONTENT, null);
    }

    /**
     * Returns the sanitized product error without caching.
     *
     * @param error public error
     * @return typed status and safe body
     */
    @ExceptionHandler(AssistantApiException.class)
    public ResponseEntity<ApiError> handle(AssistantApiException error) {
        return respond(error.status(), new ApiError(error.code(), error.getMessage()));
    }

    /**
     * Rejects malformed JSON without reflecting its content.
     *
     * @return validation error
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> malformed() {
        return handle(invalid());
    }

    /**
     * Conceals database and transaction internals.
     *
     * @return temporary storage failure
     */
    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    public ResponseEntity<ApiError> unavailable() {
        return respond(HttpStatus.SERVICE_UNAVAILABLE,
            new ApiError("INTERNAL_ERROR", "Project collaboration is temporarily unavailable."));
    }

    private static void requireFields(JsonNode input, int fields) {
        if (input == null || !input.isObject() || input.size() != fields) {
            throw invalid();
        }
    }

    private static String field(JsonNode input, String name) {
        JsonNode value = input.path(name);
        if (!value.isTextual()) {
            throw invalid();
        }
        return value.textValue();
    }

    private static AssistantApiException invalid() {
        return new AssistantApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
            "Invalid project collaboration request.");
    }

    private static <T> ResponseEntity<T> respond(HttpStatus status, T result) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(result);
    }
}

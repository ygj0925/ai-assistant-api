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

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import top.continew.admin.assistant.account.AssistantAccounts.Principal;
import top.continew.admin.assistant.account.AssistantAccounts.User;
import top.continew.admin.assistant.account.AssistantApiException;
import top.continew.admin.assistant.organization.AssistantOrganizations.Member;
import top.continew.admin.assistant.privacy.AssistantDeletionJournal;
import top.continew.admin.assistant.privacy.AssistantErasure;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Applies bounded directory deactivation to explicitly bound membership generations.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
@Service
@ConditionalOnProperty(name = "assistant.enabled", havingValue = "true")
public class AssistantOrganizationDirectory {

    static final String USER_SCHEMA = "urn:ietf:params:scim:schemas:core:2.0:User";
    static final String LIST_SCHEMA = "urn:ietf:params:scim:api:messages:2.0:ListResponse";
    static final String PATCH_SCHEMA = "urn:ietf:params:scim:api:messages:2.0:PatchOp";
    private static final Pattern ID =
        Pattern.compile("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}");
    private static final int MAX_BINDINGS = 256;
    private static final String SELECT =
        """
            SELECT id, account_id, external_id, membership_revision, created_at, disabled_at, issuer_digest
            FROM assistant_organization_directory_user WHERE directory_id = ? AND workspace_id = ?
            """;
    private static final RowMapper<Binding> BINDING = (row, index) -> new Binding(
        row.getString("id"), row.getString("account_id"), row.getString("external_id"),
        row.getString("membership_revision"), row.getLong("created_at"),
        row.getObject("disabled_at", Long.class), row.getString("issuer_digest"));

    private final JdbcTemplate jdbc;
    private final AssistantOrganizations organizations;
    private final AssistantErasure erasure;
    private final AssistantDeletionJournal journal;
    private final boolean enabled;
    private final String directoryId;
    private final String workspaceId;
    private final AssistantDirectoryTokens tokens;

    AssistantOrganizationDirectory(JdbcTemplate jdbc, AssistantOrganizations organizations,
        AssistantErasure erasure, AssistantDeletionJournal journal, AssistantDirectoryTokens tokens,
        @Value("${assistant.organization-directory.enabled:false}") boolean enabled,
        @Value("${assistant.organization-directory.directory-id:}") String directoryId,
        @Value("${assistant.organization-directory.workspace-id:}") String workspaceId) {
        this.jdbc = jdbc;
        this.organizations = organizations;
        this.erasure = erasure;
        this.journal = journal;
        this.tokens = tokens;
        this.enabled = enabled;
        this.directoryId = directoryId;
        this.workspaceId = workspaceId;
    }

    /**
     * Reports whether the selected organization owner can manage the pinned directory.
     *
     * @param user current consumer and workspace
     * @return whether the fixed directory is available for this owner
     */
    public boolean availableIn(User user) {
        return available() && workspaceId.equals(user.workspaceId())
            && organizations.policyOwner(user);
    }

    boolean supportsSso(String issuer) {
        return available() && tokens.trustsIssuer(issuer);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    OwnerBinding bind(Principal principal, String account, String revision, String externalId,
        boolean confirm) {
        if (!availableIn(principal.user())) {
            throw unavailable();
        }
        if (!validId(account) || !validId(revision) || !validId(externalId) || !confirm) {
            throw error(HttpStatus.BAD_REQUEST, "invalidValue",
                "Confirm an exact existing member and directory identifier.");
        }
        Member current = organizations.lockDirectoryBindingOwner(principal, account);
        if (current == null || !revision.equals(current.membershipRevision())) {
            throw stale();
        }
        Binding previous = byExternalId(externalId);
        String issuerFingerprint = tokens.issuerFingerprint();
        if (previous != null) {
            if (account.equals(previous.account()) && revision.equals(previous.revision())
                && previous.disabledAt() == null) {
                if (previous.issuerFingerprint() == null) {
                    jdbc.update(
                        """
                            UPDATE assistant_organization_directory_user SET issuer_digest = ?
                            WHERE id = ? AND directory_id = ? AND workspace_id = ? AND issuer_digest IS NULL
                            """,
                        issuerFingerprint, previous.id(), directoryId, workspaceId);
                } else if (!issuerFingerprint.equals(previous.issuerFingerprint())) {
                    throw stale();
                }
                return receipt(previous);
            }
            throw stale();
        }
        if (!jdbc.query(SELECT + " AND account_id = ? AND membership_revision = ?", BINDING,
            directoryId, workspaceId, account, revision).isEmpty()) {
            throw stale();
        }
        Integer count = jdbc.queryForObject(
            """
                SELECT COUNT(*) FROM assistant_organization_directory_user WHERE directory_id = ? AND workspace_id = ?
                """,
            Integer.class, directoryId, workspaceId);
        if (count == null || count >= MAX_BINDINGS) {
            throw error(HttpStatus.CONFLICT, "tooMany",
                "This directory has reached its binding limit.");
        }
        Binding binding = new Binding(UUID.randomUUID().toString(), account, externalId, revision,
            System.currentTimeMillis(), null, issuerFingerprint);
        jdbc.update(
            """
                INSERT INTO assistant_organization_directory_user
                    (id, directory_id, workspace_id, account_id, external_id, membership_revision, created_at, issuer_digest)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
            binding.id(), directoryId, workspaceId, account, externalId, revision,
            binding.createdAt(), issuerFingerprint);
        return receipt(binding);
    }

    ScimUser read(String authorization, String directory, String id) {
        authenticate(authorization, directory);
        return view(requireBinding(id, false));
    }

    List<ScimUser> find(String authorization, String directory, String externalId) {
        authenticate(authorization, directory);
        Binding match = byExternalId(externalId);
        return match == null || !tokens.issuerFingerprint().equals(match.issuerFingerprint())
            || journal.blocksCollaborationParticipant(match.account())
                ? List.of() : List.of(view(match));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    ScimUser deactivate(String authorization, String directory, String id, String ifMatch) {
        authenticate(authorization, directory);
        // Resolve only lock coordinates here. A concurrent retirement may temporarily hold their display.
        Binding observed =
            findBinding(id, false).orElseThrow(AssistantOrganizationDirectory::missing);
        Member member = organizations.lockDirectoryMember(workspaceId, observed.account());
        Binding binding = requireBinding(id, true);
        if (!observed.account().equals(binding.account())
            || !observed.revision().equals(binding.revision())
            || member != null && !binding.revision().equals(member.membershipRevision())) {
            throw stale();
        }
        boolean active = binding.disabledAt() == null && member != null;
        if (ifMatch != null && !version(binding, active).equals(ifMatch)) {
            throw error(HttpStatus.PRECONDITION_FAILED, "invalidVers",
                "The directory resource changed.");
        }
        // Recheck the signed token after potentially waiting for membership and binding locks.
        authenticate(authorization, directory);
        if (!active) {
            return projection(binding, false);
        }
        erasure.leaveWorkspace(binding.account(), workspaceId, binding.revision());
        long now = System.currentTimeMillis();
        jdbc.update("""
            UPDATE assistant_organization_directory_user SET disabled_at = ?
            WHERE id = ? AND directory_id = ? AND workspace_id = ? AND issuer_digest = ?
            """, now, binding.id(), directoryId, workspaceId, tokens.issuerFingerprint());
        organizations.recordDirectoryDeactivation(workspaceId, binding.account(), directoryId);
        return projection(new Binding(binding.id(), binding.account(), binding.externalId(),
            binding.revision(), binding.createdAt(), now, binding.issuerFingerprint()), false);
    }

    void authenticate(String authorization, String directory) {
        if (!available()) {
            throw unavailable();
        }
        tokens.verify(authorization);
        if (!directoryId.equals(directory)) {
            throw missing();
        }
    }

    private boolean available() {
        return enabled && tokens.available() && validId(directoryId) && validId(workspaceId)
            && organizations.available();
    }

    private OwnerBinding receipt(Binding row) {
        return new OwnerBinding(row.id(), directoryId, row.externalId(), row.revision());
    }

    private Binding byExternalId(String id) {
        return jdbc.query(SELECT + " AND external_id = ?", BINDING, directoryId, workspaceId, id)
            .stream().findFirst().orElse(null);
    }

    private Binding requireBinding(String id, boolean lock) {
        return findBinding(id, lock)
            .filter(row -> !journal.blocksCollaborationParticipant(row.account()))
            .orElseThrow(AssistantOrganizationDirectory::missing);
    }

    private Optional<Binding> findBinding(String id, boolean lock) {
        if (!validId(id)) {
            return Optional.empty();
        }
        return jdbc
            .query(SELECT + " AND id = ? AND issuer_digest = ?" + (lock ? " FOR UPDATE" : ""),
                BINDING, directoryId, workspaceId, id, tokens.issuerFingerprint())
            .stream().findFirst();
    }

    private ScimUser view(Binding binding) {
        Member member = organizations.directoryMember(workspaceId, binding.account());
        return projection(binding, binding.disabledAt() == null && member != null
            && binding.revision().equals(member.membershipRevision()));
    }

    private static ScimUser projection(Binding binding, boolean active) {
        var created = Instant.ofEpochMilli(binding.createdAt());
        var modified =
            binding.disabledAt() == null ? null : Instant.ofEpochMilli(binding.disabledAt());
        return new ScimUser(List.of(USER_SCHEMA), binding.id(), binding.externalId(),
            binding.externalId(), active,
            new Meta("User", created, modified, version(binding, active)));
    }

    private static String version(Binding binding, boolean active) {
        return '"' + binding.id() + (active ? ".active" : ".inactive") + '"';
    }

    static boolean validId(String input) {
        return input != null && ID.matcher(input).matches();
    }

    private static AssistantApiException unavailable() {
        return error(HttpStatus.FORBIDDEN, "forbidden",
            "This organization directory is unavailable.");
    }

    private static AssistantApiException missing() {
        return error(HttpStatus.NOT_FOUND, "notFound", "The directory resource was not found.");
    }

    private static AssistantApiException stale() {
        return error(HttpStatus.CONFLICT, "invalidVers",
            "The exact member binding changed or is unavailable.");
    }

    private static AssistantApiException error(HttpStatus status, String code, String text) {
        return new AssistantApiException(status, code, text);
    }

    record OwnerBinding(String id, String directoryId, String externalId,
        String membershipRevision) {
    }

    record ScimUser(List<String> schemas, String id, String externalId, String userName,
        boolean active, Meta meta) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Meta(String resourceType, Instant created, Instant lastModified, String version) {
    }

    private record Binding(String id, String account, String externalId, String revision,
        long createdAt, Long disabledAt, String issuerFingerprint) {
    }
}

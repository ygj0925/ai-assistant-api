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
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import top.continew.admin.assistant.account.AssistantAccounts.Principal;
import top.continew.admin.assistant.account.AssistantAccounts.User;
import top.continew.admin.assistant.account.AssistantApiException;
import top.continew.admin.assistant.privacy.AssistantDeletionJournal;
import top.continew.admin.assistant.privacy.AssistantErasure;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Enforces exact versions of explicitly configured organization capabilities.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
@Service
@ConditionalOnProperty(name = "assistant.enabled", havingValue = "true")
public class AssistantOrganizationPolicies {

    private static final String DEFAULT_REVISION = "00000000-0000-0000-0000-000000000000";
    private static final Pattern MODEL_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}");
    private static final int MAX_MODELS = 16;
    private static final int MAX_JSON_LENGTH = 4096;
    private static final Enforcement DEFAULT = new Enforcement(null, null, true, false, null);
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final AssistantOrganizations organizations;
    private final AssistantDeletionJournal journal;
    private final AssistantErasure erasure;
    private final boolean authoringEnabled;

    /**
     * Configures storage, publication authority and a separate management switch.
     *
     * @param jdbc organization and policy store
     * @param mapper typed model identifier storage
     * @param organizations owner authority and audit
     * @param journal bound durable policy retirement
     * @param erasure replayable retirement receipt writer
     * @param authoringEnabled whether owner policy changes are available
     */
    public AssistantOrganizationPolicies(JdbcTemplate jdbc, ObjectMapper mapper,
        AssistantOrganizations organizations, AssistantDeletionJournal journal,
        AssistantErasure erasure,
        @Value("${assistant.organization-policies.enabled:false}") boolean authoringEnabled) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.organizations = organizations;
        this.journal = journal;
        this.erasure = erasure;
        this.authoringEnabled = authoringEnabled;
    }

    /**
     * Advertises the policy editor only to a current designated organization owner.
     *
     * @param user selected workspace consumer
     * @return whether owner policy authoring is available
     */
    public boolean availableIn(User user) {
        return authoringEnabled && organizations.policyOwner(user);
    }

    /**
     * Reads the current authorized policy and its exact revision for review.
     *
     * @param actor current organization owner
     * @return policy summary and compare-and-swap revision
     */
    public View read(Principal actor) {
        requireOwner(actor, false);
        View result = effective(actor.user().workspaceId(), false).view();
        requireOwner(actor, false);
        return result;
    }

    /**
     * Records an affirmative replacement of one reviewed organization policy.
     *
     * @param actor current owner and device
     * @param baseRevision reviewed exact revision
     * @param models explicitly allowed model identifiers
     * @param connector whether new local connector work can proceed
     * @param confirm affirmative scoped decision
     * @return committed current policy
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public View update(Principal actor, String baseRevision, List<String> models, boolean connector,
        boolean confirm) {
        requireOwner(actor, true);
        if (!confirm || !canonicalId(baseRevision)) {
            throw invalid();
        }
        List<String> allowed = normalize(models);
        String workspace = actor.user().workspaceId();
        Enforcement prior = effective(workspace, false);
        if (!prior.view().revision().equals(baseRevision)) {
            throw new AssistantApiException(HttpStatus.CONFLICT, "ORGANIZATION_POLICY_CHANGED",
                "The organization policy changed. Review its current revision.");
        }
        if (!prior.reviewRequired() && Objects.equals(allowed, prior.modelIds())
            && connector == prior.sandboxConnectorAllowed()) {
            return prior.view();
        }
        String encoded;
        try {
            encoded = mapper.writeValueAsString(allowed);
        } catch (JsonProcessingException failure) {
            throw unavailable();
        }
        erasure.retireOrganizationPolicy(actor.user().id(), workspace, baseRevision);
        String revision = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        if (prior.revision() == null) {
            jdbc.update("""
                INSERT INTO assistant_organization_policy
                    (workspace_id, revision, allowed_models, sandbox_connector_allowed, updated_at)
                VALUES (?, ?, ?, ?, ?)
                """, workspace, revision, encoded, connector, now);
        } else {
            int updated = jdbc.update("""
                UPDATE assistant_organization_policy
                SET revision = ?, allowed_models = ?, sandbox_connector_allowed = ?, updated_at = ?
                WHERE workspace_id = ? AND revision = ?
                """, revision, encoded, connector, now, workspace, baseRevision);
            if (updated != 1) {
                throw new AssistantApiException(HttpStatus.CONFLICT, "ORGANIZATION_POLICY_CHANGED",
                    "The organization policy changed. Review its current revision.");
            }
        }
        organizations.recordPolicyChange(actor);
        return new Enforcement(revision, allowed, connector, false, Instant.ofEpochMilli(now))
            .view();
    }

    /**
     * Resolves a workspace's current effective policy independently of management switches.
     *
     * @param workspace exact authenticated workspace
     * @param lock whether to serialize this transaction with owner policy changes
     * @return active model and local connector policy
     */
    public Enforcement effective(String workspace, boolean lock) {
        String owner = jdbc.queryForList("""
            SELECT owner_account_id FROM assistant_organization WHERE workspace_id = ?
            """, String.class, workspace).stream().findFirst().orElse(null);
        if (owner == null) {
            return DEFAULT;
        }
        if (lock) {
            jdbc.queryForList(
                "SELECT workspace_id FROM assistant_organization WHERE workspace_id = ? FOR UPDATE",
                String.class, workspace);
        }
        Stored stored = jdbc.query("""
            SELECT revision, allowed_models, sandbox_connector_allowed, updated_at
            FROM assistant_organization_policy WHERE workspace_id = ?
            """ + (lock ? " FOR UPDATE" : ""),
            (row, index) -> new Stored(row.getString("revision"), row.getString("allowed_models"),
                row.getBoolean("sandbox_connector_allowed"),
                Instant.ofEpochMilli(row.getLong("updated_at"))),
            workspace).stream().findFirst().orElse(null);
        String revision = stored == null ? null : stored.revision();
        String current = revision == null ? DEFAULT_REVISION : revision;
        if (journal.blocksOrganizationPolicy(owner, workspace, current)) {
            return new Enforcement(revision, List.of(), false, true, null);
        }
        if (stored == null) {
            return DEFAULT;
        }
        try {
            return new Enforcement(revision, decode(stored.models()), stored.connector(), false,
                stored.updatedAt());
        } catch (IllegalArgumentException | JsonProcessingException
            | AssistantApiException failure) {
            return new Enforcement(revision, List.of(), false, true, stored.updatedAt());
        }
    }

    /**
     * Returns the stable authorization failure for current organization policy.
     *
     * @return typed policy error
     */
    public static AssistantApiException denied() {
        return new AssistantApiException(HttpStatus.FORBIDDEN, "ORGANIZATION_POLICY_DENIED",
            "The current organization policy does not allow this operation.");
    }

    private void requireOwner(Principal actor, boolean lock) {
        if (!authoringEnabled) {
            throw new AssistantApiException(HttpStatus.FORBIDDEN, "CAPABILITY_UNAVAILABLE",
                "Organization policy management is unavailable.");
        }
        organizations.requirePolicyOwner(actor, lock);
    }

    private List<String> decode(String text) throws JsonProcessingException {
        if (text == null || text.length() > MAX_JSON_LENGTH) {
            throw invalid();
        }
        JsonNode data = mapper.readTree(text);
        if (data == null || !data.isArray() || data.size() > MAX_MODELS) {
            throw invalid();
        }
        List<String> ids = new ArrayList<>();
        for (JsonNode item : data) {
            if (!item.isTextual()) {
                throw invalid();
            }
            ids.add(item.textValue());
        }
        return normalize(ids);
    }

    private static List<String> normalize(List<String> models) {
        if (models == null || models.size() > MAX_MODELS || models.stream()
            .anyMatch(model -> model == null || !MODEL_ID.matcher(model).matches())) {
            throw invalid();
        }
        List<String> sorted = models.stream().distinct().sorted().toList();
        if (sorted.size() != models.size()) {
            throw invalid();
        }
        return sorted;
    }

    private static boolean canonicalId(String value) {
        try {
            return value != null && value.length() == 36
                && UUID.fromString(value).toString().equals(value);
        } catch (IllegalArgumentException failure) {
            return false;
        }
    }

    private static AssistantApiException invalid() {
        return new AssistantApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
            "Confirm a reviewed organization policy and up to 16 exact model identifiers.");
    }

    private static AssistantApiException unavailable() {
        return new AssistantApiException(HttpStatus.SERVICE_UNAVAILABLE, "CAPABILITY_UNAVAILABLE",
            "Organization policy is temporarily unavailable.");
    }

    /**
     * Internal authorization snapshot held at admission or immediately before an effect.
     *
     * @param revision recorded policy generation, null for defaults
     * @param modelIds explicit sorted allowlist, null for deployment defaults
     * @param sandboxConnectorAllowed permission for new local connector effects
     * @param reviewRequired whether the stored generation has been retired
     * @param updatedAt last policy update if known
     */
    public record Enforcement(String revision, List<String> modelIds,
        boolean sandboxConnectorAllowed,
        boolean reviewRequired, Instant updatedAt) {

        /**
         * Checks one requested or resolved deployment model identifier.
         *
         * @param id exact configured or reviewed route identifier
         * @return whether the current scoped policy allows it
         */
        public boolean allowsModel(String id) {
            return !reviewRequired && (modelIds == null || modelIds.contains(id));
        }

        /**
         * Checks that the exact authority under which a request was admitted is still active.
         *
         * @param admitted saved generation for the run or action
         * @return true when that same authority remains effective
         */
        public boolean matches(String admitted) {
            return !reviewRequired && Objects.equals(revision, admitted);
        }

        private View view() {
            return new View(revision == null ? DEFAULT_REVISION : revision, modelIds,
                sandboxConnectorAllowed, reviewRequired, updatedAt);
        }
    }

    /**
     * Owner-readable policy and approval generation.
     *
     * @param revision compare-and-swap revision
     * @param allowedModelIds reviewed allowlist or null when the deployment defaults apply
     * @param sandboxConnectorAllowed whether the local connector may be used
     * @param reviewRequired whether a recovered policy requires owner review
     * @param updatedAt last committed update if present
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record View(String revision,
        @JsonInclude(JsonInclude.Include.ALWAYS) List<String> allowedModelIds,
        boolean sandboxConnectorAllowed, boolean reviewRequired, Instant updatedAt) {
    }

    private record Stored(String revision, String models, boolean connector, Instant updatedAt) {
    }
}

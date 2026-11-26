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
import com.fasterxml.jackson.databind.ObjectMapper;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisException;
import org.redisson.client.codec.StringCodec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import top.continew.admin.assistant.account.AssistantAccounts;
import top.continew.admin.assistant.account.AssistantAccounts.Preferences;
import top.continew.admin.assistant.account.AssistantAccounts.Principal;
import top.continew.admin.assistant.account.AssistantAccounts.User;
import top.continew.admin.assistant.account.AssistantApiException;
import top.continew.admin.assistant.organization.AssistantOrganizations.Member;
import top.continew.admin.assistant.privacy.AssistantDeletionJournal;
import top.continew.admin.assistant.privacy.AssistantErasure;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Keeps consent, workspaces, and credentials separate from ordinary consumer and operator sessions.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
@Service
@ConditionalOnProperty(name = "assistant.enabled", havingValue = "true")
class AssistantOrganizationSso {

    private static final Duration FLOW_TTL = Duration.ofMinutes(5);
    private static final Duration SESSION_TTL = Duration.ofMinutes(10);
    private static final Pattern RANDOM = Pattern.compile("[A-Za-z0-9_-]{42}[AEIMQUYcgkosw048]");
    private static final Pattern BEARER = Pattern.compile("Bearer (osso_[A-Za-z0-9_-]{43})");
    private static final int MAX_ACTIVE_SESSIONS = 8;
    private static final String BINDINGS =
        """
            SELECT d.id, d.account_id, d.membership_revision, d.external_id, d.issuer_digest
            FROM assistant_organization_directory_user d JOIN assistant_account a ON a.id = d.account_id
            WHERE d.directory_id = ? AND d.workspace_id = ? AND d.disabled_at IS NULL AND a.deleted_at IS NULL
            """;
    private static final RowMapper<Binding> BINDING =
        (row, index) -> new Binding(row.getString("id"),
            row.getString("account_id"), row.getString("membership_revision"),
            row.getString("external_id"), row.getString("issuer_digest"));

    private final JdbcTemplate jdbc;
    private final RedissonClient redis;
    private final ObjectMapper json;
    private final AssistantAccounts accounts;
    private final AssistantOrganizations organizations;
    private final AssistantOrganizationDirectory directory;
    private final AssistantDeletionJournal journal;
    private final AssistantErasure erasure;
    private final AssistantOrganizationOidc oidc;
    private final TransactionTemplate transactions;
    private final SecureRandom random = new SecureRandom();
    private final String flowIncarnation = UUID.randomUUID().toString();
    private final boolean enabled;
    private final String workspace;
    private final String directoryId;
    private final String issuerFingerprint;

    AssistantOrganizationSso(JdbcTemplate jdbc, RedissonClient redis, ObjectMapper json,
        AssistantAccounts accounts, AssistantOrganizations organizations,
        AssistantOrganizationDirectory directory, AssistantDeletionJournal journal,
        AssistantErasure erasure, AssistantOrganizationOidc oidc,
        PlatformTransactionManager transactionManager,
        @Value("${assistant.organization-sso.enabled:false}") boolean enabled,
        @Value("${assistant.organization-directory.workspace-id:}") String workspace,
        @Value("${assistant.organization-directory.directory-id:}") String directoryId) {
        this.jdbc = jdbc;
        this.redis = redis;
        this.json = json;
        this.accounts = accounts;
        this.organizations = organizations;
        this.directory = directory;
        this.journal = journal;
        this.erasure = erasure;
        this.oidc = oidc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setIsolationLevel(TransactionTemplate.ISOLATION_READ_COMMITTED);
        this.enabled = enabled;
        this.workspace = workspace;
        this.directoryId = directoryId;
        this.issuerFingerprint = AssistantPinnedJwt.issuerFingerprint(oidc.issuer());
    }

    boolean available() {
        return enabled && oidc.available() && directory.supportsSso(oidc.issuer());
    }

    LinkStatus linkStatus(Principal principal) {
        if (!revocationAvailable() || !workspace.equals(principal.user().workspaceId())) {
            return new LinkStatus(false, false);
        }
        Binding binding = findForAccount(principal.user().id());
        if (binding == null || !active(binding)) {
            return new LinkStatus(false, false);
        }
        return new LinkStatus(available() && issuerMatches(binding),
            consent(binding, false, false) != null);
    }

    Start start(String browserBinding) {
        requireAvailable();
        requireRandom(browserBinding);
        return storeFlow(browserBinding, null);
    }

    Start startLink(Principal principal, String browserBinding, String password, boolean confirm) {
        requireAvailable();
        requireRandom(browserBinding);
        requireConfirmed(confirm);
        LinkAttempt link = Objects.requireNonNull(transactions.execute(transaction -> {
            Binding current = lockForPrincipal(principal, password);
            if (!issuerMatches(current)) {
                throw forbidden();
            }
            return new LinkAttempt(principal.user().id(), principal.sessionId(), current.id(),
                fence(current));
        }));
        return storeFlow(browserBinding, link);
    }

    Completion complete(String state, String code, String responseIssuer, String browserBinding) {
        requireAvailable();
        if (!isRandom(state) || !isRandom(browserBinding)
            || !oidc.supportsResponseIssuer(responseIssuer)) {
            throw AssistantOrganizationOidc.denied();
        }
        Flow flow = consumeFlow(state, browserBinding);
        String subject = oidc.exchange(code, responseIssuer, flow.verifier(), flow.nonce());
        return Objects.requireNonNull(transactions.execute(transaction -> finish(flow, subject)));
    }

    Scoped authenticate(String authorization, String suppliedWorkspace) {
        requireAvailable();
        return scope(authorization, suppliedWorkspace, true);
    }

    private Scoped scope(String authorization, String suppliedWorkspace, boolean requireIssuer) {
        if (authorization == null || authorization.length() != 55) {
            throw AssistantOrganizationOidc.denied();
        }
        var bearer = BEARER.matcher(authorization);
        if (!bearer.matches()) {
            throw AssistantOrganizationOidc.denied();
        }
        var session = jdbc
            .query(
                """
                    SELECT s.id AS session_id, c.id AS consent_id, d.id AS binding_id, d.account_id,
                        c.link_fence, c.issuer_digest AS consent_issuer, d.issuer_digest AS binding_issuer,
                        d.membership_revision, d.external_id, a.username, a.locale, a.theme
                    FROM assistant_organization_sso_session s
                    JOIN assistant_organization_sso_consent c ON c.id = s.consent_id
                    JOIN assistant_organization_directory_user d ON d.id = c.binding_id
                    JOIN assistant_account a ON a.id = d.account_id
                    WHERE s.token_digest = ? AND s.expires_at > ? AND d.workspace_id = ? AND d.directory_id = ?
                        AND d.disabled_at IS NULL AND a.deleted_at IS NULL
                    """,
                (row, index) -> new SessionScope(row.getString("session_id"),
                    row.getString("consent_id"), row.getString("link_fence"),
                    row.getString("consent_issuer"),
                    new Binding(row.getString("binding_id"), row.getString("account_id"),
                        row.getString("membership_revision"), row.getString("external_id"),
                        row.getString("binding_issuer")),
                    new User(row.getString("account_id"), row.getString("username"), workspace,
                        new Preferences(row.getString("locale"), row.getString("theme")))),
                digest(bearer.group(1)), System.currentTimeMillis(), workspace, directoryId)
            .stream().findFirst().orElseThrow(AssistantOrganizationOidc::denied);
        if (!active(session.binding())
            || !Objects.equals(session.linkFence(), fence(session.binding()))
            || requireIssuer && (!issuerMatches(session.binding())
                || !issuerFingerprint.equals(session.issuer()))
            || journal.blocksOrganizationSso(session.user().id(), workspace, session.consent())
            || journal.blocksOrganizationSso(session.user().id(), workspace, session.id())) {
            throw AssistantOrganizationOidc.denied();
        }
        if (suppliedWorkspace != null && !workspace.equals(suppliedWorkspace)) {
            throw forbidden();
        }
        return new Scoped(session.user(), session.id(), session.consent());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    void logout(String authorization, String suppliedWorkspace) {
        requireRevocation();
        Scoped initial = scope(authorization, suppliedWorkspace, false);
        organizations.lockDirectoryMember(workspace, initial.user().id());
        Scoped current = scope(authorization, suppliedWorkspace, false);
        if (!initial.equals(current)) {
            throw AssistantOrganizationOidc.denied();
        }
        erasure.revokeOrganizationSso(current.user().id(), workspace, current.sessionId());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    void unlink(Principal principal, String password, boolean confirm) {
        requireRevocation();
        requireConfirmed(confirm);
        Binding binding = lockForPrincipal(principal, password);
        erasure.fenceOrganizationSsoLink(binding.account(), workspace, binding.id());
    }

    private Completion finish(Flow flow, String subject) {
        requireAvailable();
        Binding observed =
            jdbc.query(BINDINGS + " AND d.external_id = ?", BINDING, directoryId, workspace,
                subject).stream().findFirst().orElseThrow(AssistantOrganizationOidc::denied);
        Binding binding = lockExact(observed);
        if (!issuerMatches(binding)) {
            throw AssistantOrganizationOidc.denied();
        }
        if (flow.link() == null) {
            return issue(binding, flow.eligibleConsents());
        }
        LinkAttempt link = flow.link();
        if (!binding.id().equals(link.binding()) || !binding.account().equals(link.account())
            || !Objects.equals(link.fence(), fence(binding))
            || jdbc.queryForList(
                """
                    SELECT id FROM assistant_session WHERE id = ? AND account_id = ? AND expires_at > ? FOR UPDATE
                    """,
                String.class, link.passwordSession(), binding.account(), System.currentTimeMillis())
                .isEmpty()) {
            throw AssistantOrganizationOidc.denied();
        }
        if (consent(binding, true, true) == null) {
            if (consent(binding, true, false) != null) {
                throw new AssistantApiException(HttpStatus.CONFLICT,
                    "ORGANIZATION_SSO_REAPPROVAL_REQUIRED",
                    "Remove the prior association before approving organization sign-in again.");
            }
            jdbc.update(
                """
                    INSERT INTO assistant_organization_sso_consent
                        (id, binding_id, created_at, issuer_digest, link_fence)
                    VALUES (?, ?, ?, ?, ?)
                    """,
                UUID.randomUUID().toString(), binding.id(), System.currentTimeMillis(),
                issuerFingerprint,
                link.fence());
        }
        return new Completion("link", true, null, null, null);
    }

    private Completion issue(Binding binding, List<String> eligibleConsents) {
        String consent = consent(binding, true, true);
        if (consent == null || eligibleConsents == null || !eligibleConsents.contains(consent)) {
            throw AssistantOrganizationOidc.denied();
        }
        long now = System.currentTimeMillis();
        jdbc.update(
            "DELETE FROM assistant_organization_sso_session WHERE consent_id = ? AND expires_at <= ?",
            consent, now);
        Integer active = jdbc.queryForObject("""
            SELECT COUNT(*) FROM assistant_organization_sso_session WHERE consent_id = ?
            """, Integer.class, consent);
        if (active == null || active >= MAX_ACTIVE_SESSIONS) {
            throw new AssistantApiException(HttpStatus.TOO_MANY_REQUESTS,
                "ORGANIZATION_SSO_SESSION_LIMIT",
                "Wait for an organization session to expire.");
        }
        String token = "osso_" + random();
        var expires = Instant.ofEpochMilli(now).plus(SESSION_TTL);
        jdbc.update(
            """
                INSERT INTO assistant_organization_sso_session (id, consent_id, token_digest, created_at, expires_at)
                VALUES (?, ?, ?, ?, ?)
                """,
            UUID.randomUUID().toString(), consent, digest(token), now, expires.toEpochMilli());
        return new Completion("login", null, token, expires, workspace);
    }

    private Binding lockForPrincipal(Principal principal, String password) {
        if (!workspace.equals(principal.user().workspaceId())) {
            throw forbidden();
        }
        Binding selected = findForAccount(principal.user().id());
        if (selected == null) {
            throw forbidden();
        }
        Binding binding = lockExact(selected);
        accounts.requirePrivacyPassword(principal, password);
        return binding;
    }

    private Binding findForAccount(String account) {
        Member member = organizations.directoryMember(workspace, account);
        if (member == null) {
            return null;
        }
        return jdbc.query(BINDINGS + " AND d.account_id = ? AND d.membership_revision = ?", BINDING,
            directoryId, workspace, account, member.membershipRevision()).stream().findFirst()
            .orElse(null);
    }

    private Binding lockExact(Binding observed) {
        Member member = organizations.lockDirectoryMember(workspace, observed.account());
        Binding binding = jdbc.query(BINDINGS + " AND d.id = ? FOR UPDATE", BINDING, directoryId,
            workspace, observed.id()).stream().findFirst().orElse(null);
        if (!observed.equals(binding) || member == null
            || !member.membershipRevision().equals(binding.revision())
            || journal.blocksCollaborationParticipant(binding.account())) {
            throw AssistantOrganizationOidc.denied();
        }
        return binding;
    }

    private boolean active(Binding binding) {
        if (journal.blocksCollaborationParticipant(binding.account())) {
            return false;
        }
        Member member = organizations.directoryMember(workspace, binding.account());
        return member != null && binding.revision().equals(member.membershipRevision());
    }

    private String consent(Binding binding, boolean lock, boolean requireIssuer) {
        String current = fence(binding);
        return jdbc
            .query(
                """
                    SELECT id, issuer_digest, link_fence FROM assistant_organization_sso_consent WHERE binding_id = ?
                    """
                    + (lock ? " FOR UPDATE" : ""),
                (row, index) -> new Consent(row.getString("id"),
                    row.getString("issuer_digest"), row.getString("link_fence")),
                binding.id())
            .stream()
            .filter(consent -> Objects.equals(current, consent.linkFence())
                && (!requireIssuer || issuerFingerprint.equals(consent.issuer()))
                && !journal.blocksOrganizationSso(binding.account(), workspace, consent.id()))
            .map(Consent::id).findFirst().orElse(null);
    }

    private boolean issuerMatches(Binding binding) {
        return issuerFingerprint.equals(binding.issuer());
    }

    private String fence(Binding binding) {
        return journal.organizationSsoLinkFence(binding.account(), workspace, binding.id());
    }

    private List<String> eligibleConsents() {
        List<String> current = jdbc.queryForList("""
            SELECT c.id FROM assistant_organization_sso_consent c
            JOIN assistant_organization_directory_user d ON d.id = c.binding_id
            WHERE d.workspace_id = ? AND d.directory_id = ? AND d.disabled_at IS NULL
                AND c.issuer_digest = ? AND d.issuer_digest = ? LIMIT 257
            """, String.class, workspace, directoryId, issuerFingerprint, issuerFingerprint);
        if (current.size() > 256) {
            throw unavailable();
        }
        return current;
    }

    private Start storeFlow(String browserBinding, LinkAttempt link) {
        String state = random();
        String verifier = random();
        String nonce = random();
        List<String> consents = link == null ? eligibleConsents() : List.of();
        try {
            bucket(state, browserBinding)
                .set(json.writeValueAsString(new Flow(verifier, nonce, link, consents)), FLOW_TTL);
        } catch (JsonProcessingException | RedisException failure) {
            throw unavailable();
        }
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(hash(verifier));
        return new Start(oidc.authorize(state, nonce, challenge, link != null));
    }

    private Flow consumeFlow(String state, String browserBinding) {
        try {
            String value = bucket(state, browserBinding).getAndDelete();
            if (value == null) {
                throw AssistantOrganizationOidc.denied();
            }
            return json.readValue(value, Flow.class);
        } catch (JsonProcessingException | RedisException failure) {
            throw unavailable();
        }
    }

    private RBucket<String> bucket(String state, String browserBinding) {
        // A restored account database must never reactivate an unfinished flow left in Redis.
        return redis.getBucket("assistant:organization-sso:" + directoryId + ":" + workspace + ":"
            + digest(
                issuerFingerprint + ":" + flowIncarnation + ":" + state + ":" + browserBinding),
            StringCodec.INSTANCE);
    }

    private void requireAvailable() {
        if (!available()) {
            throw forbidden();
        }
    }

    private boolean revocationAvailable() {
        return AssistantOrganizationDirectory.validId(workspace)
            && AssistantOrganizationDirectory.validId(directoryId) && organizations.available();
    }

    private void requireRevocation() {
        if (!revocationAvailable()) {
            throw forbidden();
        }
    }

    private static void requireConfirmed(boolean confirm) {
        if (!confirm) {
            throw new AssistantApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                "Confirm this organization sign-in change.");
        }
    }

    private static void requireRandom(String value) {
        if (!isRandom(value)) {
            throw new AssistantApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                "The browser sign-in correlation is invalid.");
        }
    }

    private static boolean isRandom(String value) {
        return value != null && value.length() == 43 && RANDOM.matcher(value).matches();
    }

    private String random() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String digest(String value) {
        return HexFormat.of().formatHex(hash(value));
    }

    private static byte[] hash(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable.", failure);
        }
    }

    private static AssistantApiException forbidden() {
        return new AssistantApiException(HttpStatus.FORBIDDEN, "ORGANIZATION_SSO_UNAVAILABLE",
            "Organization sign-in is unavailable in this scope.");
    }

    private static AssistantApiException unavailable() {
        return new AssistantApiException(HttpStatus.SERVICE_UNAVAILABLE,
            "ORGANIZATION_SSO_UNAVAILABLE",
            "The organization sign-in could not be completed.");
    }

    record Start(String authorizationUrl) {
    }

    record LinkStatus(boolean eligible, boolean linked) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Completion(String mode, Boolean linked, String sessionToken, Instant expiresAt,
        String workspaceId) {
    }

    record Scoped(User user, String sessionId, String consentId) {
    }

    private record Binding(String id, String account, String revision, String subject,
        String issuer) {
    }

    private record Consent(String id, String issuer, String linkFence) {
    }

    private record SessionScope(String id, String consent, String linkFence, String issuer,
        Binding binding, User user) {
    }

    private record LinkAttempt(String account, String passwordSession, String binding,
        String fence) {
    }

    private record Flow(String verifier, String nonce, LinkAttempt link,
        List<String> eligibleConsents) {
    }
}

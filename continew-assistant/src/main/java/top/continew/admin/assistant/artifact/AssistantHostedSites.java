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

package top.continew.admin.assistant.artifact;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import top.continew.admin.assistant.account.AssistantAccounts;
import top.continew.admin.assistant.account.AssistantAccounts.Principal;
import top.continew.admin.assistant.account.AssistantAccounts.User;
import top.continew.admin.assistant.account.AssistantApiException;
import top.continew.admin.assistant.artifact.AssistantArtifacts.PublicationSource;
import top.continew.admin.assistant.moderation.ModerationTarget;
import top.continew.admin.assistant.privacy.AssistantDeletionJournal;
import top.continew.admin.assistant.privacy.AssistantErasure;

import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * Serves one expressly reviewed immutable webpage version per personally owned project draft.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
@Service
@ConditionalOnProperty(name = "assistant.enabled", havingValue = "true")
public class AssistantHostedSites {

    static final String PUBLIC_PATH = "/assistant-sites";
    private static final String SELECT = """
        SELECT id, revision, owner_id, workspace_id, project_id, artifact_id, version_id,
            content_digest, source_read_revision, source_policy_revision, published_at
        FROM assistant_hosted_site WHERE
        """;
    private static final RowMapper<Site> SITE = (row, index) -> new Site(row.getString("id"),
        row.getString("revision"), row.getString("owner_id"), row.getString("workspace_id"),
        row.getString("project_id"), row.getString("artifact_id"), row.getString("version_id"),
        row.getString("content_digest"), row.getString("source_read_revision"),
        row.getString("source_policy_revision"), row.getLong("published_at"));

    private final JdbcTemplate jdbc;
    private final AssistantAccounts accounts;
    private final AssistantArtifacts artifacts;
    private final AssistantErasure erasure;
    private final AssistantDeletionJournal journal;
    private final URI origin;

    /**
     * Wires private sources, revision retirement and one operator-configured serving origin.
     *
     * @param jdbc durable site records
     * @param accounts active consumer scope
     * @param artifacts reviewed private versions
     * @param erasure durable revision retirement
     * @param journal retirement checks independent of the database snapshot
     * @param enabled operator hosting switch
     * @param origin configured isolated public origin
     */
    public AssistantHostedSites(JdbcTemplate jdbc, AssistantAccounts accounts,
        AssistantArtifacts artifacts, AssistantErasure erasure, AssistantDeletionJournal journal,
        @Value("${assistant.artifacts.hosting.enabled:false}") boolean enabled,
        @Value("${assistant.artifacts.hosting.origin:}") String origin) {
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.artifacts = artifacts;
        this.erasure = erasure;
        this.journal = journal;
        this.origin = enabled ? hostingOrigin(origin) : null;
    }

    /**
     * Indicates whether this consumer's selected personal workspace supports hosting.
     *
     * @param user authenticated consumer scope
     * @return true for a ready hosting deployment and eligible workspace
     */
    public boolean available(User user) {
        return ready() && personal(user.id(), user.workspaceId());
    }

    /**
     * Indicates whether authenticated viewers can review the exact current public release.
     *
     * @return true while public hosting and its revocation controls are available
     */
    public boolean reportingAvailable() {
        return ready();
    }

    /**
     * Returns the current managed release, if any, for an owned webpage.
     *
     * @param user authenticated owner
     * @param project project containing the webpage
     * @param artifact private webpage identifier
     * @return currently recorded release or an empty state
     */
    public Status status(User user, String project, String artifact) {
        requireOwner(user, project, artifact);
        Site site = ownedSite(user, project, artifact);
        if (site == null) {
            var item = artifacts.get(user, artifact).summary();
            if (!project.equals(item.projectId())
                || !AssistantWebpage.FORMAT.equals(item.format())) {
                throw missing();
            }
        }
        return new Status(site == null ? null : summary(site, currentBytes(site) != null));
    }

    /**
     * Previews the exact currently eligible document and returns its content digest for confirmation.
     *
     * @param user authenticated owner
     * @param project project containing the webpage
     * @param artifact private webpage identifier
     * @param version explicitly selected immutable version
     * @return title and digest of the public candidate
     */
    public Preview preview(User user, String project, String artifact, String version) {
        requireOwner(user, project, artifact);
        validateId(version);
        var source = requireSource(user.id(), user.workspaceId(), project, artifact, version);
        return new Preview(version, source.title(), source.versionNumber(),
            digest(source.renderedWebsite()));
    }

    /**
     * Publishes, updates or rolls back a stable address using a reviewed exact version and base revision.
     *
     * @param principal authenticated owner and current device
     * @param project project containing the webpage
     * @param artifact private webpage identifier
     * @param input owner confirmation and exact preview relationship
     * @return confirmed active release
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Release publish(Principal principal, String project, String artifact, Publish input) {
        if (input == null || !Boolean.TRUE.equals(input.confirm()) || input.contentDigest() == null
            || !input.contentDigest().matches("[0-9a-f]{64}")) {
            throw invalid("Review the exact version and confirm its public release.");
        }
        validateId(input.versionId());
        if (input.expectedRevision() != null) {
            validateId(input.expectedRevision());
        }
        User user = lockEditor(principal);
        requireOwner(user, project, artifact);
        Site current = ownedSite(user, project, artifact);
        if (!Objects.equals(current == null ? null : current.revision(),
            input.expectedRevision())) {
            throw conflict();
        }
        var source =
            requireSource(user.id(), user.workspaceId(), project, artifact, input.versionId());
        if (!input.contentDigest().equals(digest(source.renderedWebsite()))) {
            throw conflict();
        }
        if (current != null && current.version().equals(input.versionId())
            && currentBytes(current) != null) {
            return summary(current, true);
        }
        String id = current == null ? UUID.randomUUID().toString() : current.id();
        if (current != null) {
            erasure.retireHostedSite(user, current.revision());
        }
        var next = new Site(id, UUID.randomUUID().toString(), user.id(), user.workspaceId(),
            project, artifact, input.versionId(), input.contentDigest(), source.readRevision(),
            source.policyRevision(), System.currentTimeMillis());
        jdbc.update(
            """
                INSERT INTO assistant_hosted_site (id, revision, owner_id, workspace_id, project_id,
                    artifact_id, version_id, content_digest, source_read_revision, source_policy_revision, published_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
            next.id(), next.revision(), next.owner(), next.workspace(), next.project(),
            next.artifact(), next.version(), next.digest(), next.sourceRead(), next.sourcePolicy(),
            next.publishedAt());
        return summary(next, true);
    }

    /**
     * Retires the exact observed release and its address while retaining the underlying project versions.
     *
     * @param principal authenticated owner and current device
     * @param project project containing the webpage
     * @param artifact private webpage identifier
     * @param revision exact current release revision
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void revoke(Principal principal, String project, String artifact, String revision) {
        validateId(revision);
        User user = lockEditor(principal);
        requireOwner(user, project, artifact);
        Site site = ownedSite(user, project, artifact);
        if (site == null) {
            throw missing();
        }
        if (!revision.equals(site.revision())) {
            throw conflict();
        }
        erasure.retireHostedSite(user, revision);
    }

    /**
     * Reads the presently authorized static document for an anonymous delivery request.
     *
     * @param host requested serving host
     * @param id opaque public address identifier
     * @return bounded standalone HTML and its revision, or null when unavailable
     */
    PublicPage read(String host, String id) {
        if (!ready() || !origin.getHost().equalsIgnoreCase(host) || !canonicalId(id)) {
            return null;
        }
        Site site = byId(id);
        byte[] html = site == null ? null : currentBytes(site);
        return html == null ? null : new PublicPage(html, site.revision());
    }

    /**
     * Previews metadata sufficient to confirm an exact report for a canonical public address.
     *
     * @param reporter current authenticated consumer
     * @param id opaque public address identifier
     * @return only the public address and its current revision
     */
    public ReportTarget reportableVersion(Principal reporter, String id) {
        requireReporter(reporter);
        validateId(id);
        Site site = byId(id);
        reportEvidence(site);
        return new ReportTarget(site.id(), site.revision(), publicUrl(site));
    }

    /**
     * Resolves the exact public generation reviewed by an authenticated reporter.
     *
     * @param reporter current authenticated consumer
     * @param id canonical public address identifier
     * @param revision release identifier captured by the review
     * @return scoped identity and a fingerprint without the authored HTML
     */
    public ModerationTarget reportTarget(Principal reporter, String id, String revision) {
        requireReporter(reporter);
        validateId(id);
        validateId(revision);
        Site site = byId(id);
        if (site == null || !revision.equals(site.revision())) {
            throw missing();
        }
        return reportEvidence(site);
    }

    /**
     * Resolves a current report under the operator's established workspace grant.
     *
     * @param workspace authorized moderation workspace
     * @param revision persisted exact release generation
     * @return currently eligible release evidence
     */
    public ModerationTarget reportedTarget(String workspace, String revision) {
        return reportEvidence(byRevision(workspace, revision, false));
    }

    /**
     * Retires only a report's exact still-visible generation under the held owner account lock.
     *
     * @param target already authorized moderation target
     */
    @Transactional(propagation = Propagation.MANDATORY, noRollbackFor = AssistantApiException.class)
    public void withdrawReported(ModerationTarget target) {
        var latest = reportEvidence(byRevision(target.workspaceId(), target.targetId(), true));
        if (!target.equals(latest)) {
            throw missing();
        }
        erasure.retireHostedSite(target.ownerId(), target.workspaceId(), target.targetId());
    }

    private Site byId(String id) {
        return jdbc.query(SELECT + " id = ?", SITE, id).stream().findFirst().orElse(null);
    }

    private Site byRevision(String workspace, String revision, boolean lock) {
        return jdbc.query(SELECT + " workspace_id = ? AND revision = ?"
            + (lock ? " FOR UPDATE" : ""), SITE, workspace, revision).stream().findFirst()
            .orElse(null);
    }

    private ModerationTarget reportEvidence(Site site) {
        var source = site == null || !reportingAvailable() ? null : visible(site);
        if (source == null || currentBytes(site, source) == null) {
            throw missing();
        }
        return new ModerationTarget(site.owner(), site.workspace(), site.revision(),
            source.conversationId(), site.publishedAt(), "hosted_site:" + site.digest());
    }

    private void requireReporter(Principal principal) {
        if (!reportingAvailable()) {
            throw missing();
        }
        User reporter = principal.user();
        var memberships = jdbc.queryForList("""
            SELECT m.membership_revision FROM assistant_session s
            JOIN assistant_account a ON a.id = s.account_id AND a.deleted_at IS NULL
            JOIN assistant_workspace_member m ON m.account_id = s.account_id AND m.workspace_id = ?
            WHERE s.account_id = ? AND s.id = ? AND s.expires_at > ?
            """, String.class, reporter.workspaceId(), reporter.id(), principal.sessionId(),
            System.currentTimeMillis());
        if (memberships.isEmpty() || journal.blocksAccount(reporter.id())
            || !accounts.allowsMembership(reporter.id(), reporter.workspaceId(),
                memberships.get(0))) {
            throw new AssistantApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED",
                "A current reporter session is required.");
        }
    }

    private byte[] currentBytes(Site site) {
        var source = visible(site);
        return source == null ? null : currentBytes(site, source);
    }

    private byte[] currentBytes(Site site, PublicationSource source) {
        byte[] html = source.renderedWebsite();
        String renderedDigest = digest(html);
        synchronized (journal) {
            return site.digest().equals(renderedDigest) && !held(site, source) ? html : null;
        }
    }

    private PublicationSource visible(Site site) {
        if (!personal(site.owner(), site.workspace()) || journal.blocksHostedSite(site.owner(),
            site.workspace(), site.revision())) {
            return null;
        }
        var source = artifacts.publicationSource(site.owner(), site.workspace(), site.project(),
            site.artifact(), site.version(), true);
        return source == null || !AssistantWebpage.FORMAT.equals(source.format())
            || held(site, source)
                ? null : source;
    }

    private boolean held(Site site, PublicationSource source) {
        return !ready() || journal.blocksHostedSite(site.owner(), site.workspace(), site.revision())
            || journal.blocksArtifact(site.owner(), site.workspace(), site.artifact(),
                source.conversationId())
            || !Objects.equals(site.sourceRead(), source.readRevision())
            || !Objects.equals(site.sourcePolicy(), source.policyRevision())
            || source.conversationId() != null && journal.blocksConversationRead(site.owner(),
                site.workspace(), source.conversationId(), site.sourceRead())
            || !source.placementCurrent(journal, site.owner(), site.workspace())
            || journal.blocksArtifactPublicationPolicy(site.owner(), site.workspace(),
                source.conversationId(), site.sourcePolicy());
    }

    private boolean ready() {
        return origin != null && artifacts.webpageEditingAvailable();
    }

    private boolean personal(String owner, String workspace) {
        return !journal.blocksAccount(owner) && !jdbc.queryForList(
            """
                SELECT w.id FROM assistant_workspace w JOIN assistant_account a ON a.id = w.personal_owner_id
                JOIN assistant_workspace_member m ON m.workspace_id = w.id AND m.account_id = a.id
                WHERE w.id = ? AND a.id = ? AND a.deleted_at IS NULL
                """,
            String.class, workspace, owner).isEmpty();
    }

    private void requireOwner(User user, String project, String artifact) {
        validateId(project);
        validateId(artifact);
        if (!available(user)) {
            throw new AssistantApiException(HttpStatus.FORBIDDEN, "CAPABILITY_UNAVAILABLE",
                "Personal project hosting is unavailable.");
        }
    }

    private User lockEditor(Principal principal) {
        User user = principal.user();
        accounts.lockActive(user.id());
        if (jdbc.queryForList("""
            SELECT id FROM assistant_session
            WHERE account_id = ? AND id = ? AND expires_at > ? FOR UPDATE
            """, String.class, user.id(), principal.sessionId(), System.currentTimeMillis())
            .isEmpty()) {
            throw new AssistantApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED",
                "A current session is required.");
        }
        return user;
    }

    private Site ownedSite(User user, String project, String artifact) {
        return jdbc
            .query(
                SELECT
                    + " owner_id = ? AND workspace_id = ? AND project_id = ? AND artifact_id = ?",
                SITE, user.id(), user.workspaceId(), project, artifact)
            .stream().findFirst().orElse(null);
    }

    private PublicationSource requireSource(String owner, String workspace, String project,
        String artifact, String version) {
        var source =
            artifacts.publicationSource(owner, workspace, project, artifact, version, true);
        if (source == null || !AssistantWebpage.FORMAT.equals(source.format())) {
            throw missing();
        }
        return source;
    }

    private Release summary(Site site, boolean accessible) {
        return new Release(site.revision(), site.version(), publicUrl(site),
            accessible, Instant.ofEpochMilli(site.publishedAt()));
    }

    private String publicUrl(Site site) {
        return origin.resolve(PUBLIC_PATH + "/" + site.id()).toString();
    }

    private static URI hostingOrigin(String input) {
        var candidate = URI.create(input);
        if (candidate.getHost() != null) {
            String host = candidate.getHost().toLowerCase(Locale.ROOT);
            boolean testHost = "localhost".equals(host) || host.endsWith(".localhost");
            if (("https".equals(candidate.getScheme())
                || "http".equals(candidate.getScheme()) && testHost)
                && candidate.getUserInfo() == null && candidate.getRawQuery() == null
                && candidate.getFragment() == null
                && (candidate.getPath().isEmpty() || "/".equals(candidate.getPath()))
                && candidate.getPort() <= 65_535 && candidate.getPort() != 0) {
                return candidate;
            }
        }
        throw new IllegalArgumentException(
            "An isolated HTTPS hosting origin or a localhost test origin is required.");
    }

    private static String digest(byte[] body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable.", unavailable);
        }
    }

    private static boolean canonicalId(String id) {
        if (id == null || id.length() != 36) {
            return false;
        }
        try {
            return UUID.fromString(id).toString().equals(id);
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static void validateId(String id) {
        if (!canonicalId(id)) {
            throw invalid("Choose a valid project, webpage, version or release identifier.");
        }
    }

    private static AssistantApiException missing() {
        return new AssistantApiException(HttpStatus.NOT_FOUND, "NOT_FOUND",
            "Eligible project webpage or release not found.");
    }

    private static AssistantApiException invalid(String message) {
        return new AssistantApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message);
    }

    private static AssistantApiException conflict() {
        return new AssistantApiException(HttpStatus.CONFLICT, "CONFLICT",
            "Review the current release and preview again.");
    }

    /**
     * Management state for one owner draft.
     *
     * @param release current retained publication, or null
     */
    public record Status(@JsonInclude(JsonInclude.Include.ALWAYS) Release release) {
    }

    /**
     * Exact immutable release candidate.
     *
     * @param versionId selected private version
     * @param title version title
     * @param versionNumber version ordinal
     * @param contentDigest SHA-256 of the exact standalone HTML
     */
    public record Preview(String versionId, String title, int versionNumber, String contentDigest) {
    }

    /**
     * Explicit release of a reviewed version.
     *
     * @param versionId selected private version
     * @param contentDigest digest from the review step
     * @param expectedRevision current base, or null for a fresh publication
     * @param confirm explicit acknowledgment that the address serves an anonymous audience
     */
    public record Publish(String versionId, String contentDigest, String expectedRevision,
        Boolean confirm) {
    }

    /**
     * Management projection of a hosted release.
     *
     * @param revision current publish pointer revision
     * @param versionId selected private version
     * @param url address at the configured serving origin
     * @param accessible whether the source currently allows a public response
     * @param publishedAt time of the current publication
     */
    public record Release(String revision, String versionId, String url, boolean accessible,
        Instant publishedAt) {
    }

    /**
     * Exact public snapshot consumed by the isolated serving controller.
     *
     * @param html reviewed immutable HTML bytes
     * @param revision matching public release generation
     */
    record PublicPage(byte[] html, String revision) {
    }

    /**
     * Minimal authenticated preview for a public report.
     *
     * @param siteId opaque public address identifier
     * @param revision current release generation
     * @param publicUrl address at the configured serving origin
     */
    public record ReportTarget(String siteId, String revision, String publicUrl) {
    }

    private record Site(String id, String revision, String owner, String workspace, String project,
        String artifact, String version, String digest, String sourceRead, String sourcePolicy,
        long publishedAt) {
    }
}

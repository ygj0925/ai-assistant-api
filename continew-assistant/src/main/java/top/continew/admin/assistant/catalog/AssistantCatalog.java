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

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import top.continew.admin.assistant.account.AssistantAccounts;
import top.continew.admin.assistant.account.AssistantAccounts.Principal;
import top.continew.admin.assistant.account.AssistantAccounts.User;
import top.continew.admin.assistant.account.AssistantApiException;
import top.continew.admin.assistant.customassistant.AssistantCustomAssistants;
import top.continew.admin.assistant.organization.AssistantOrganizations;
import top.continew.admin.assistant.privacy.AssistantDeletionJournal;
import top.continew.admin.assistant.privacy.AssistantErasure;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Publishes independently reviewed, safe projections of immutable assistant and connector versions.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
@Service
@ConditionalOnProperty(name = "assistant.enabled", havingValue = "true")
public class AssistantCatalog {

    private static final String ASSISTANT = "assistant";
    private static final String CONNECTOR = "connector";
    private static final String CONNECTOR_ID = "fixture_notice";
    private static final String PENDING = "pending";
    private static final String PUBLISHED = "published";
    private static final String REJECTED = "rejected";
    private static final String WITHDRAWN = "withdrawn";
    private static final String SUPERSEDED = "superseded";
    private static final int AUTHOR_CAPACITY = 48;
    private static final Set<String> KINDS = Set.of(ASSISTANT, CONNECTOR);
    private static final Set<String> REASONS = Set.of("spam", "misleading", "privacy", "unsafe",
        "other");
    private static final Set<String> DECISIONS = Set.of("approve", "reject", "withdraw");
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9._~-]{1,128}");
    private static final String ENTRIES = """
        SELECT id, account_id, workspace_id, membership_revision, kind, source_id, active_release_id
        FROM assistant_catalog_entry WHERE id = ?
        """;
    private static final String RELEASES =
        """
            SELECT r.id, r.entry_id, r.workspace_id, r.source_version_id, r.base_release_id, r.version_number,
                r.content_digest, r.title, r.summary, r.state, r.submitted_at, r.decided_at,
                r.published_at, e.account_id, e.membership_revision, e.kind, e.source_id
            FROM assistant_catalog_release r JOIN assistant_catalog_entry e ON e.id = r.entry_id
                AND e.workspace_id = r.workspace_id
            WHERE 1 = 1
            """;
    private static final String SUBMITTED_WINDOW = """
        AND (r.submitted_at < ? OR (r.submitted_at = ? AND r.id < ?))
        ORDER BY r.submitted_at DESC, r.id DESC LIMIT ?
        """;
    private static final String REPORTS = """
        SELECT id, release_id, reason, created_at FROM assistant_catalog_report WHERE release_id = ?
        """;
    private static final RowMapper<Entry> ENTRY_ROW = (row, index) -> new Entry(
        row.getString("id"), row.getString("account_id"), row.getString("workspace_id"),
        row.getString("membership_revision"), row.getString("kind"), row.getString("source_id"),
        row.getString("active_release_id"));
    private static final RowMapper<Release> RELEASE_ROW = (row, index) -> new Release(
        row.getString("id"), row.getString("entry_id"), row.getString("workspace_id"),
        row.getString("source_version_id"), row.getString("base_release_id"),
        row.getInt("version_number"),
        row.getString("content_digest"), row.getString("title"), row.getString("summary"),
        row.getString("state"), row.getLong("submitted_at"),
        row.getObject("decided_at", Long.class),
        row.getObject("published_at", Long.class), row.getString("account_id"),
        row.getString("membership_revision"), row.getString("kind"), row.getString("source_id"));
    private static final RowMapper<Report> REPORT_ROW = (row, index) -> new Report(
        row.getString("id"), row.getString("release_id"), row.getString("reason"),
        Instant.ofEpochMilli(row.getLong("created_at")));

    private final JdbcTemplate jdbc;
    private final AssistantAccounts accounts;
    private final AssistantCustomAssistants customs;
    private final AssistantOrganizations organizations;
    private final AssistantDeletionJournal journal;
    private final AssistantErasure erasure;
    private final boolean enabled;
    private final boolean reviewEnabled;
    private final String connectorWorkspace;

    /**
     * Configures authoring, separate administration review, and the first-party connector slot.
     *
     * @param jdbc catalogue persistence
     * @param accounts live consumer scope
     * @param customs private exact assistant versions
     * @param organizations connector author ownership
     * @param journal independent durable release withdrawals
     * @param erasure durable event application
     * @param enabled authoring and discovery switch
     * @param reviewEnabled separate administration review switch
     * @param connectorWorkspace sole organization permitted to submit the fixture descriptor
     */
    public AssistantCatalog(JdbcTemplate jdbc, AssistantAccounts accounts,
        AssistantCustomAssistants customs, AssistantOrganizations organizations,
        AssistantDeletionJournal journal, AssistantErasure erasure,
        @Value("${assistant.catalog.enabled:false}") boolean enabled,
        @Value("${assistant.catalog.review-enabled:false}") boolean reviewEnabled,
        @Value("${assistant.catalog.connector-author-workspace:}") String connectorWorkspace) {
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.customs = customs;
        this.organizations = organizations;
        this.journal = journal;
        this.erasure = erasure;
        this.enabled = enabled;
        this.reviewEnabled = reviewEnabled;
        this.connectorWorkspace = connectorWorkspace;
    }

    /**
     * Indicates whether the catalogue currently accepts new submissions and serves public entries.
     *
     * @return deployment capability
     */
    public boolean available() {
        return enabled && journal.acceptsErasure();
    }

    /**
     * Indicates whether this exact current scope can submit the fixed first-party descriptor.
     *
     * @param user current selected account and workspace
     * @return designated organization owner eligibility
     */
    public boolean connectorSubmissionAvailable(User user) {
        return available() && user != null && uuid(connectorWorkspace)
            && connectorWorkspace.equals(user.workspaceId()) && organizations.policyOwner(user);
    }

    /**
     * Shows exactly the source data proposed for reviewed publication.
     *
     * @param actor current authenticated author
     * @param input explicit source and description
     * @return bounded review projection and confirmation digest
     */
    public Preview preview(Principal actor, SourceRequest input) {
        requireAvailable();
        scope(actor, false);
        return proposal(actor.user(), input);
    }

    /**
     * Submits the same reviewed source version, with a stable retry and withdrawal target.
     *
     * @param actor current authenticated author
     * @param input explicit reviewed source
     * @param digest previously previewed exact disclosure digest
     * @param confirmation affirmative author consent
     * @param key caller retry key
     * @return existing repeat or a new pending release
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public CatalogRelease submit(Principal actor, SourceRequest input, String digest,
        boolean confirmation, String key) {
        requireAvailable();
        if (!confirmation || key == null || !KEY.matcher(key).matches()) {
            throw invalid();
        }
        User user = actor.user();
        String membership = scope(actor, true);
        Preview preview = proposal(user, input);
        if (!preview.contentDigest().equals(digest)) {
            throw conflict("Review the exact source version before submitting it.");
        }
        boolean connector = CONNECTOR.equals(input.kind());
        String entryId = connector ? identifier("connector", CONNECTOR_ID)
            : identifier("assistant", user.id(), user.workspaceId(), membership,
                input.assistantId());
        String id = identifier("release", entryId, key);
        if (journal.blocksCatalogRelease(user.id(), user.workspaceId(), id)) {
            throw conflict("This release request was already withdrawn.");
        }

        Entry entry = entry(entryId, true).orElse(null);
        if (entry == null) {
            if (authorCount(user, membership) >= AUTHOR_CAPACITY) {
                throw conflict("The catalogue submission limit has been reached.");
            }
            jdbc.update("""
                INSERT INTO assistant_catalog_entry
                    (id, account_id, workspace_id, membership_revision, kind, source_id)
                VALUES (?, ?, ?, ?, ?, ?)
                """, entryId, user.id(), user.workspaceId(), membership, input.kind(),
                connector ? CONNECTOR_ID : input.assistantId());
        } else {
            requireAuthor(user, membership, entry);
            var previous = findRelease(user.workspaceId(), id);
            if (previous.isPresent()) {
                Release current = previous.orElseThrow();
                if (!preview.contentDigest().equals(current.digest())) {
                    throw conflict("The submission key belongs to a different review.");
                }
                return view(current);
            }
            if (authorCount(user, membership) >= AUTHOR_CAPACITY) {
                throw conflict("The catalogue submission limit has been reached.");
            }
        }
        long now = System.currentTimeMillis();
        jdbc.update("""
            INSERT INTO assistant_catalog_release
                (id, entry_id, workspace_id, source_version_id, base_release_id, version_number,
                 content_digest, title, summary, state, submitted_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'pending', ?)
            """, id, entryId, user.workspaceId(), input.versionId(), effectiveRelease(entry),
            preview.versionNumber(),
            preview.contentDigest(), preview.title(), preview.summary(), now);
        audit(id, user.workspaceId(), "submitted", null, now);
        return view(release(user.workspaceId(), id));
    }

    /**
     * Lists only the author’s current-scope submission history.
     *
     * @param actor selected author
     * @param limit maximum result count
     * @param cursor continuation token
     * @return same-scope release status page
     */
    public Page<CatalogRelease> authored(Principal actor, Integer limit, String cursor) {
        String membership = scope(actor, false);
        User user = actor.user();
        Cursor at = Cursor.parse(cursor);
        int count = size(limit);
        var rows = jdbc.query(RELEASES + """
            AND e.account_id = ? AND e.workspace_id = ? AND e.membership_revision = ?
            """ + SUBMITTED_WINDOW, RELEASE_ROW, user.id(), user.workspaceId(), membership,
            at.time(), at.time(), at.id(), count + 1);
        return releasePage(rows, count);
    }

    /**
     * Withdraws a submitted or published release by its exact current author.
     *
     * @param actor selected author
     * @param id exact release
     * @param confirm explicit consent to withdraw
     * @return current status
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public CatalogRelease withdraw(Principal actor, String id, boolean confirm) {
        if (!confirm) {
            throw invalid();
        }
        User user = actor.user();
        String membership = scope(actor, true);
        Release draft = release(user.workspaceId(), id);
        Entry entry = entry(draft.entryId(), true).orElseThrow(AssistantCatalog::missing);
        requireAuthor(user, membership, entry);
        Release current = release(user.workspaceId(), id);
        if (PENDING.equals(current.state()) || PUBLISHED.equals(current.state())) {
            retire(current, WITHDRAWN, "author_withdrawn", null, System.currentTimeMillis());
        }
        return view(release(user.workspaceId(), id));
    }

    /**
     * Lists public, still-eligible releases without their full instruction text.
     *
     * @param kind optional approved item category
     * @param limit maximum result count
     * @param cursor continuation token
     * @return a page of reviewed entries
     */
    public Page<Item> items(String kind, Integer limit, String cursor) {
        requireAvailable();
        if (kind != null && !KINDS.contains(kind)) {
            throw invalid();
        }
        int count = size(limit);
        Cursor at = Cursor.parse(cursor);
        List<Object> args = new ArrayList<>();
        String filter = "";
        if (kind != null) {
            filter = " AND e.kind = ? ";
            args.add(kind);
        }
        args.addAll(List.of(at.time(), at.time(), at.id(), count + 1));
        var rows = jdbc.query(RELEASES + """
            AND e.active_release_id = r.id AND r.state = 'published'
            """ + filter + """
            AND (r.published_at < ? OR (r.published_at = ? AND r.id < ?))
            ORDER BY r.published_at DESC, r.id DESC LIMIT ?
            """, RELEASE_ROW, args.toArray());
        var page = rows.subList(0, Math.min(rows.size(), count));
        List<Item> visible = page.stream().flatMap(item -> publicSource(item).stream()
            .map(source -> projected(item, source, false))).toList();
        String next = rows.size() > count
            ? new Cursor(page.getLast().published(), page.getLast().id()).encode() : null;
        return new Page<>(visible, next);
    }

    /**
     * Returns the exact approved text projection of the current release of an entry.
     *
     * @param id public entry ID
     * @return approved discoverable item
     */
    public Item item(String id) {
        requireAvailable();
        Release row = published(id).orElseThrow(AssistantCatalog::missing);
        Preview source = publicSource(row).orElseThrow(AssistantCatalog::missing);
        return projected(row, source, true);
    }

    /**
     * Records one bounded report per live viewer and exact reviewed release.
     *
     * @param actor current viewer
     * @param id public entry identifier
     * @param releaseId exact report target
     * @param reason supported reason
     * @return sanitized report receipt and whether it was created
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Submission report(Principal actor, String id, String releaseId, String reason) {
        requireAvailable();
        if (!uuid(releaseId) || reason == null || !REASONS.contains(reason)) {
            throw invalid();
        }
        scope(actor, true);
        Entry entry = entry(id, true).orElseThrow(AssistantCatalog::missing);
        Release row = published(entry.id()).filter(value -> releaseId.equals(value.id()))
            .orElseThrow(AssistantCatalog::missing);
        publicSource(row).orElseThrow(AssistantCatalog::missing);

        String account = actor.user().id();
        var before = jdbc.query(REPORTS + " AND account_id = ?", REPORT_ROW, releaseId, account)
            .stream().findFirst();
        if (before.isPresent()) {
            Report existing = before.orElseThrow();
            if (!existing.reason().equals(reason)) {
                throw conflict("A report already exists for this release.");
            }
            return new Submission(existing, false);
        }
        String reportId = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        jdbc.update("""
            INSERT INTO assistant_catalog_report
                (id, release_id, workspace_id, account_id, reason, created_at)
            VALUES (?, ?, ?, ?, ?, ?)
            """, reportId, releaseId, row.workspace(), account, reason, now);
        return new Submission(new Report(reportId, releaseId, reason, Instant.ofEpochMilli(now)),
            true);
    }

    /**
     * Lists catalogue submissions for the operator’s authorized workspace only.
     *
     * @param operator administrative session and tenant
     * @param workspace explicitly selected workspace
     * @param limit maximum result count
     * @param cursor continuation token
     * @return scoped release statuses
     */
    public Page<CatalogRelease> review(Operator operator, String workspace, Integer limit,
        String cursor) {
        requireReviewer(operator, workspace, false, false);
        int count = size(limit);
        Cursor at = Cursor.parse(cursor);
        List<Release> rows = jdbc.query(RELEASES + " AND r.workspace_id = ?"
            + SUBMITTED_WINDOW, RELEASE_ROW, workspace, at.time(), at.time(), at.id(), count + 1);
        return releasePage(rows, count);
    }

    /**
     * Returns the currently available exact preview and scoped audit and reports to a reviewer.
     *
     * @param operator administrative session and tenant
     * @param workspace explicitly selected workspace
     * @param releaseId exact submission
     * @return scoped reviewer dossier
     */
    public Review dossier(Operator operator, String workspace, String releaseId) {
        requireReviewer(operator, workspace, false, false);
        Release row = release(workspace, releaseId);
        Preview preview = eligible(row).orElse(null);
        var audit = jdbc.query("""
            SELECT action, operator_id, created_at FROM assistant_catalog_audit
            WHERE workspace_id = ? AND release_id = ?
            ORDER BY created_at, id LIMIT 24
            """, (data, index) -> new Audit(data.getString("action"),
            data.getObject("operator_id", Long.class),
            Instant.ofEpochMilli(data.getLong("created_at"))), workspace, releaseId);
        var reports = jdbc.query(REPORTS + """
            AND workspace_id = ? ORDER BY created_at DESC, id DESC LIMIT 50
            """, REPORT_ROW, releaseId, workspace);
        requireReviewer(operator, workspace, false, false);
        return new Review(view(row), preview, audit, reports);
    }

    /**
     * Makes and audits one exact scoped operator decision.
     *
     * @param operator current administrative session and tenant
     * @param workspace explicitly selected workspace
     * @param releaseId exact pending or published release
     * @param decision approved transition
     * @param confirm explicit decision confirmation
     * @return current decision result
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public CatalogRelease decide(Operator operator, String workspace, String releaseId,
        String decision, boolean confirm) {
        if (!confirm || decision == null || !DECISIONS.contains(decision)) {
            throw invalid();
        }
        requireReviewer(operator, workspace, true, "withdraw".equals(decision));
        Release before = release(workspace, releaseId);
        accounts.lockActive(before.account());
        Entry entry = entry(before.entryId(), true).orElseThrow(AssistantCatalog::missing);
        Release current = release(workspace, releaseId);
        long now = System.currentTimeMillis();
        switch (decision) {
            case "approve" -> {
                requirePending(current);
                eligible(current).orElseThrow(AssistantCatalog::missing);
                if (!Objects.equals(current.baseReleaseId(), effectiveRelease(entry))) {
                    throw conflict(
                        "The active release changed. Review and submit the source again.");
                }
                if (entry.activeRelease() != null) {
                    Release previous = release(workspace, entry.activeRelease());
                    retire(previous, SUPERSEDED, "superseded", operator.id(), now);
                }
                jdbc.update("""
                    UPDATE assistant_catalog_release
                    SET state = 'published', decided_at = ?, published_at = ?
                    WHERE workspace_id = ? AND id = ?
                    """, now, now, workspace, releaseId);
                jdbc.update("UPDATE assistant_catalog_entry SET active_release_id = ? WHERE id = ?",
                    releaseId, entry.id());
                audit(releaseId, workspace, "approved", operator.id(), now);
            }
            case "reject" -> {
                requirePending(current);
                retire(current, REJECTED, "rejected", operator.id(), now);
            }
            case "withdraw" -> {
                if (PENDING.equals(current.state()) || PUBLISHED.equals(current.state())) {
                    retire(current, WITHDRAWN, "operator_withdrawn", operator.id(), now);
                }
            }
            default -> throw invalid();
        }
        return view(release(workspace, releaseId));
    }

    private void retire(Release release, String state, String action, Long operator, long now) {
        erasure.retireCatalogRelease(release.account(), release.workspace(), release.id());
        jdbc.update("""
            UPDATE assistant_catalog_release SET state = ?, decided_at = ?
            WHERE id = ? AND workspace_id = ?
            """, state, now, release.id(), release.workspace());
        jdbc.update("""
            UPDATE assistant_catalog_entry SET active_release_id = NULL
            WHERE id = ? AND active_release_id = ?
            """, release.entryId(), release.id());
        audit(release.id(), release.workspace(), action, operator, now);
    }

    private void audit(String id, String workspace, String action, Long operator, long now) {
        jdbc.update("""
            INSERT INTO assistant_catalog_audit
                (id, release_id, workspace_id, action, operator_id, created_at)
            VALUES (?, ?, ?, ?, ?, ?)
            """, UUID.randomUUID().toString(), id, workspace, action, operator, now);
    }

    private int authorCount(User user, String membership) {
        return jdbc.queryForObject(RELEASES_COUNT, Integer.class, user.id(), user.workspaceId(),
            membership);
    }

    private static final String RELEASES_COUNT =
        """
            SELECT COUNT(*) FROM assistant_catalog_release r JOIN assistant_catalog_entry e ON e.id = r.entry_id
            WHERE e.account_id = ? AND e.workspace_id = ? AND e.membership_revision = ?
            """;

    private void requireReviewer(Operator actor, String workspace, boolean lock,
        boolean allowEmergencyWithdrawal) {
        if ((!reviewEnabled || !enabled) && !allowEmergencyWithdrawal
            || !journal.acceptsErasure()) {
            throw unavailable();
        }
        if (!uuid(workspace)) {
            throw invalid();
        }
        String sql = """
            SELECT can_decide FROM assistant_moderation_grant
            WHERE admin_tenant_id = ? AND admin_user_id = ? AND workspace_id = ?
            """ + (lock ? " FOR UPDATE" : "");
        var grants = jdbc.queryForList(sql, Boolean.class, actor.tenantId(), actor.id(), workspace);
        if (grants.isEmpty() || lock && !Boolean.TRUE.equals(grants.getFirst())) {
            throw forbidden();
        }
    }

    private String scope(Principal actor, boolean lock) {
        User user = actor.user();
        if (lock) {
            accounts.lockActive(user.id());
            if (jdbc.queryForList("""
                SELECT id FROM assistant_session
                WHERE account_id = ? AND id = ? AND expires_at > ? FOR UPDATE
                """, String.class, user.id(), actor.sessionId(), System.currentTimeMillis())
                .isEmpty()) {
                throw new AssistantApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED",
                    "A current consumer session is required.");
            }
        }
        return membership(user.id(), user.workspaceId(), lock)
            .orElseThrow(AssistantCatalog::forbidden);
    }

    private Optional<String> membership(String account, String workspace, boolean lock) {
        if (journal.blocksCollaborationParticipant(account)) {
            return Optional.empty();
        }
        return jdbc.queryForList("""
            SELECT m.membership_revision FROM assistant_workspace_member m
            JOIN assistant_account a ON a.id = m.account_id AND a.deleted_at IS NULL
            WHERE m.account_id = ? AND m.workspace_id = ?
            """ + (lock ? " FOR UPDATE" : ""), String.class, account, workspace).stream()
            .findFirst()
            .filter(revision -> !journal.blocksWorkspaceMembership(account, workspace, revision));
    }

    private Preview proposal(User user, SourceRequest input) {
        if (input == null || input.kind() == null || !KINDS.contains(input.kind())) {
            throw invalid();
        }
        if (CONNECTOR.equals(input.kind())) {
            if (input.assistantId() != null || input.versionId() != null
                || input.summary() != null) {
                throw invalid();
            }
            if (!connectorSubmissionAvailable(user)) {
                throw forbidden();
            }
            return fixture();
        }
        if (!uuid(input.assistantId()) || !uuid(input.versionId())) {
            throw invalid();
        }
        String summary = description(input.summary());
        return assistant(user, input.assistantId(), input.versionId(), summary);
    }

    private Preview assistant(User owner, String source, String version, String summary) {
        var selected = customs.version(owner, source, version);
        if (!selected.resourceIds().isEmpty() || !selected.tools().isEmpty()) {
            throw invalid();
        }
        String digest =
            digest(ASSISTANT, version, selected.name(), summary, selected.instructions());
        return new Preview(ASSISTANT, selected.name(), summary, selected.instructions(), null,
            selected.number(), digest);
    }

    private static Preview fixture() {
        String title = "本地通知模拟器";
        String summary = "展示已审核的第一方本地通知凭据能力。目录入口仅提供说明。";
        return new Preview(CONNECTOR, title, summary, null, CONNECTOR_ID, 1,
            digest(CONNECTOR, CONNECTOR_ID, "1", title, summary));
    }

    private Optional<Preview> eligible(Release row) {
        if (journal.blocksCatalogRelease(row.account(), row.workspace(), row.id())
            || membership(row.account(), row.workspace(), false)
                .filter(row.membership()::equals).isEmpty()) {
            return Optional.empty();
        }
        Preview preview;
        try {
            User owner = new User(row.account(), null, row.workspace(), null);
            if (CONNECTOR.equals(row.kind())) {
                if (!connectorSubmissionAvailable(owner) || !CONNECTOR_ID.equals(row.sourceId())) {
                    return Optional.empty();
                }
                preview = fixture();
            } else {
                preview = assistant(owner, row.sourceId(), row.versionId(), row.summary());
            }
        } catch (AssistantApiException failure) {
            return Optional.empty();
        }
        return preview.contentDigest().equals(row.digest())
            && !journal.blocksCatalogRelease(row.account(), row.workspace(), row.id())
                ? Optional.of(preview) : Optional.empty();
    }

    private Optional<Preview> publicSource(Release row) {
        return eligible(row).filter(preview -> published(row.entryId()).map(Release::id)
            .filter(row.id()::equals).isPresent());
    }

    private String effectiveRelease(Entry entry) {
        return entry == null || entry.activeRelease() == null ? null
            : findRelease(entry.workspace(), entry.activeRelease())
                .filter(row -> PUBLISHED.equals(row.state())
                    && !journal.blocksCatalogRelease(row.account(), row.workspace(), row.id()))
                .map(Release::id).orElse(null);
    }

    private Optional<Release> published(String id) {
        if (!uuid(id)) {
            return Optional.empty();
        }
        return jdbc.query(RELEASES + """
            AND e.id = ? AND e.active_release_id = r.id AND r.state = 'published'
            """, RELEASE_ROW, id).stream().findFirst();
    }

    private Optional<Entry> entry(String id, boolean lock) {
        if (!uuid(id)) {
            return Optional.empty();
        }
        return jdbc.query(ENTRIES + (lock ? " FOR UPDATE" : ""), ENTRY_ROW, id).stream()
            .findFirst();
    }

    private Optional<Release> findRelease(String workspace, String id) {
        if (!uuid(id)) {
            return Optional.empty();
        }
        return jdbc.query(RELEASES + " AND r.workspace_id = ? AND r.id = ?", RELEASE_ROW,
            workspace, id).stream().findFirst();
    }

    private Release release(String workspace, String id) {
        return findRelease(workspace, id).orElseThrow(AssistantCatalog::missing);
    }

    private Page<CatalogRelease> releasePage(List<Release> rows, int limit) {
        var page = rows.subList(0, Math.min(rows.size(), limit));
        String next = rows.size() > limit
            ? new Cursor(page.getLast().submitted(), page.getLast().id()).encode() : null;
        return new Page<>(page.stream().map(this::view).toList(), next);
    }

    private CatalogRelease view(Release row) {
        String state = (PENDING.equals(row.state()) || PUBLISHED.equals(row.state()))
            && journal.catalogReleaseRetired(row.account(), row.workspace(), row.id())
                ? WITHDRAWN : row.state();
        return new CatalogRelease(row.id(), row.entryId(), row.kind(), state, row.number(),
            row.title(), row.summary(), Instant.ofEpochMilli(row.submitted()),
            row.decided() == null ? null : Instant.ofEpochMilli(row.decided()));
    }

    private static Item projected(Release row, Preview preview, boolean detail) {
        return new Item(row.entryId(), row.id(), row.kind(), preview.title(), preview.summary(),
            preview.versionNumber(), detail ? preview.instructions() : null, preview.connectorId(),
            Instant.ofEpochMilli(row.published()));
    }

    private void requirePending(Release row) {
        if (!PENDING.equals(row.state())
            || journal.blocksCatalogRelease(row.account(), row.workspace(), row.id())) {
            throw conflict("This exact release is no longer awaiting review.");
        }
    }

    private static void requireAuthor(User user, String membership, Entry entry) {
        if (!entry.account().equals(user.id()) || !entry.workspace().equals(user.workspaceId())
            || !entry.membership().equals(membership)) {
            throw missing();
        }
    }

    private void requireAvailable() {
        if (!available()) {
            throw unavailable();
        }
    }

    private static int size(Integer limit) {
        int value = limit == null ? 20 : limit;
        if (value < 1 || value > 32) {
            throw invalid();
        }
        return value;
    }

    private static String description(String value) {
        if (value == null || value.length() > 320 || value.isBlank()
            || value.codePointCount(0, value.length()) > 160
            || !StandardCharsets.UTF_8.newEncoder().canEncode(value)
            || value.codePoints().anyMatch(Character::isISOControl)) {
            throw invalid();
        }
        return value.strip();
    }

    private static String identifier(String... parts) {
        return UUID.nameUUIDFromBytes(digest(parts).getBytes(StandardCharsets.US_ASCII)).toString();
    }

    private static String digest(String... parts) {
        try {
            var sha = MessageDigest.getInstance("SHA-256");
            for (String part : parts) {
                byte[] bytes = part.getBytes(StandardCharsets.UTF_8);
                sha.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
                sha.update(bytes);
            }
            return HexFormat.of().formatHex(sha.digest());
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 required", failure);
        }
    }

    private static boolean uuid(String value) {
        try {
            return value != null && value.length() == 36
                && UUID.fromString(value).toString().equals(value);
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static AssistantApiException invalid() {
        return new AssistantApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
            "Choose an eligible exact release and review the requested action.");
    }

    private static AssistantApiException missing() {
        return new AssistantApiException(HttpStatus.NOT_FOUND, "NOT_FOUND",
            "The catalogue item is unavailable.");
    }

    private static AssistantApiException forbidden() {
        return new AssistantApiException(HttpStatus.FORBIDDEN, "FORBIDDEN",
            "Catalogue access is unavailable for this scope.");
    }

    private static AssistantApiException conflict(String message) {
        return new AssistantApiException(HttpStatus.CONFLICT, "CATALOG_RELEASE_CHANGED", message);
    }

    private static AssistantApiException unavailable() {
        return new AssistantApiException(HttpStatus.FORBIDDEN, "CAPABILITY_UNAVAILABLE",
            "The reviewed catalogue is unavailable.");
    }

    private record Entry(String id, String account, String workspace, String membership,
        String kind,
        String source, String activeRelease) {
    }

    private record Release(String id, String entryId, String workspace, String versionId,
        String baseReleaseId, int number, String digest, String title, String summary, String state,
        long submitted, Long decided, Long published, String account, String membership,
        String kind, String sourceId) {
    }

    private record Cursor(long time, String id) {

        private static Cursor parse(String value) {
            if (value == null) {
                return new Cursor(Long.MAX_VALUE, "z");
            }
            try {
                if (value.length() > 56) {
                    throw invalid();
                }
                int delimiter = value.indexOf(':');
                if (delimiter < 1 || !uuid(value.substring(delimiter + 1))) {
                    throw invalid();
                }
                long at = Long.parseLong(value.substring(0, delimiter));
                if (at < 1) {
                    throw invalid();
                }
                return new Cursor(at, value.substring(delimiter + 1));
            } catch (NumberFormatException failure) {
                throw invalid();
            }
        }

        private String encode() {
            return time + ":" + id;
        }
    }

    /**
     * Exact authored source and optional catalogue description.
     *
     * @param kind assistant or fixed first-party connector
     * @param assistantId owner’s private assistant ID when applicable
     * @param versionId chosen exact immutable assistant version
     * @param summary reviewed one-line catalogue description
     */
    public record SourceRequest(String kind, String assistantId, String versionId, String summary) {
    }

    /**
     * Safe exact content offered to the author and authorized operator for review.
     *
     * @param kind category of source
     * @param title approved name
     * @param summary reviewed one-line description
     * @param instructions explicitly disclosed instructions for assistants
     * @param connectorId fixed discovery-only capability ID for connectors
     * @param versionNumber immutable source version number
     * @param contentDigest exact disclosure fingerprint
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Preview(String kind, String title, String summary, String instructions,
        String connectorId, int versionNumber, String contentDigest) {

        @Override
        public String toString() {
            return "Preview[protected]";
        }
    }

    /**
     * Authorized author or operator’s exact release receipt.
     *
     * @param id exact release ID
     * @param entryId stable public catalogue reference if approved
     * @param kind source category
     * @param state current review or release state
     * @param versionNumber immutable source version number
     * @param title reviewed title
     * @param summary reviewed one-line description
     * @param submittedAt first submission time
     * @param decidedAt latest decision timestamp if available
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CatalogRelease(String id, String entryId, String kind, String state,
        int versionNumber, String title, String summary, Instant submittedAt, Instant decidedAt) {
    }

    /**
     * Published catalogue entry without private author or source identifiers.
     *
     * @param id stable entry ID
     * @param releaseId exact current approved release
     * @param kind assistant or connector
     * @param title approved title
     * @param summary approved one-line description
     * @param versionNumber reviewed immutable source version number
     * @param instructions expressly public in assistant detail
     * @param connectorId first-party capability name in connector results
     * @param publishedAt publication timestamp
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Item(String id, String releaseId, String kind, String title, String summary,
        int versionNumber, String instructions, String connectorId, Instant publishedAt) {
    }

    /**
     * Bounded release, report or catalogue result set.
     *
     * @param items selected results
     * @param nextCursor continuation token when present
     * @param <T> result record type
     */
    public record Page<T>(List<T> items, String nextCursor) {
    }

    /**
     * Administrative identity from the actual ContiNew session.
     *
     * @param id authenticated ContiNew operator
     * @param tenantId current administrative tenant
     */
    public record Operator(long id, long tenantId) {
    }

    /**
     * Authorized reviewer’s exact dossier and bounded operational history.
     *
     * @param release reviewed exact release status
     * @param preview still-visible reviewed source projection
     * @param audit bounded authorization and decision receipts
     * @param reports sanitized scoped viewer reports
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Review(CatalogRelease release, Preview preview, List<Audit> audit,
        List<Report> reports) {
    }

    /**
     * One scoped reviewer decision receipt.
     *
     * @param action decision or author transition
     * @param operatorId administrative decision maker when applicable
     * @param createdAt audit time
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Audit(String action, Long operatorId, Instant createdAt) {
    }

    /**
     * One report with no reporter or source ownership identifiers.
     *
     * @param id report identifier
     * @param releaseId exact approved release target
     * @param reason supported report reason
     * @param createdAt original report time
     */
    public record Report(String id, String releaseId, String reason, Instant createdAt) {
    }

    /**
     * Report receipt and whether a new row was inserted.
     *
     * @param receipt sanitized report
     * @param created whether this request created the report
     */
    public record Submission(Report receipt, boolean created) {
    }
}

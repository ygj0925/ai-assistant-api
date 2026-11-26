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

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import top.continew.admin.assistant.account.AssistantAccounts.Principal;
import top.continew.admin.assistant.account.AssistantApiException;
import top.continew.admin.assistant.privacy.AssistantDeletionJournal;
import top.continew.admin.assistant.privacy.AssistantErasure;
import top.continew.admin.assistant.project.AssistantProjectCollaboration.Items;
import top.continew.admin.assistant.project.AssistantProjectCollaboration.PublicationScope;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Manages bounded immutable revisions of expressly shared Markdown drafts.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
@Service
@ConditionalOnProperty(name = "assistant.enabled", havingValue = "true")
public class AssistantSharedDrafts {

    private static final int MAX_DRAFTS = 32;
    private static final int MAX_REVISIONS = 32;
    private static final int MAX_BODY_BYTES = 32 * 1024;
    private static final String RETENTION = "affected_draft_erased";
    private static final String DRAFTS = """
        FROM assistant_project_shared_draft
        WHERE workspace_id = ? AND owner_id = ? AND project_id = ?
        """;
    private static final String REVISIONS = """
        FROM assistant_project_shared_draft_revision WHERE draft_id = ?
        """;
    private static final String COLUMNS =
        "id, version_no, title, author_id, restored_from_revision_id, created_at";
    private static final RowMapper<StoredRevision> HEADER = AssistantSharedDrafts::readHeader;
    private static final RowMapper<StoredRevision> BODY = AssistantSharedDrafts::readBody;

    private final JdbcTemplate jdbc;
    private final AssistantProjectCollaboration collaboration;
    private final AssistantDeletionJournal journal;
    private final AssistantErasure erasure;
    private final boolean enabled;

    /**
     * Connects explicitly shared drafts to existing roles and durable privacy decisions.
     *
     * @param jdbc shared draft storage
     * @param collaboration authoritative project and membership scope
     * @param journal draft retirement and erased contributors
     * @param erasure scoped durable deletion
     * @param enabled explicit feature policy
     */
    public AssistantSharedDrafts(JdbcTemplate jdbc, AssistantProjectCollaboration collaboration,
        AssistantDeletionJournal journal, AssistantErasure erasure,
        @Value("${assistant.project-shared-drafts.enabled:false}") boolean enabled) {
        this.jdbc = jdbc;
        this.collaboration = collaboration;
        this.journal = journal;
        this.erasure = erasure;
        this.enabled = enabled;
    }

    /**
     * Creates a new draft from explicitly submitted shared text.
     *
     * @param principal current project owner session
     * @param projectId owned project
     * @param write explicit Markdown title and body
     * @param confirmed acceptance of the shared work and retention policy
     * @return draft with its initial revision
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Draft create(Principal principal, String projectId, Write write, boolean confirmed) {
        requireReady();
        if (!confirmed) {
            throw invalid(
                "Confirm that this draft and its retention policy are shared with the project.");
        }
        Write checked = validate(write);
        PublicationScope scope = collaboration.publicationScope(principal, projectId, true);
        scope.requireOwner();
        String workspace = principal.user().workspaceId();
        Integer count = jdbc.queryForObject("SELECT COUNT(*) " + DRAFTS, Integer.class,
            workspace, scope.ownerId(), projectId);
        if (count != null && count >= MAX_DRAFTS) {
            throw conflict("The project already has 32 shared drafts.");
        }
        String id = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        jdbc.update("""
            INSERT INTO assistant_project_shared_draft
                (id, workspace_id, owner_id, project_id, updated_at) VALUES (?, ?, ?, ?, ?)
            """, id, workspace, scope.ownerId(), projectId, now);
        StoredRevision revision = append(id, scope.ownerId(), 1, checked, null, now);
        return draft(id, scope, revision);
    }

    /**
     * Lists draft metadata allowed by current project and contributor state.
     *
     * @param principal current project member session
     * @param projectId collaboration project
     * @return bounded draft headings
     */
    public Items<Summary> list(Principal principal, String projectId) {
        requireReady();
        PublicationScope before = collaboration.publicationScope(principal, projectId, false);
        String workspace = principal.user().workspaceId();
        List<String> rows = jdbc.queryForList("SELECT id " + DRAFTS
            + " ORDER BY updated_at DESC, id DESC LIMIT ?", String.class, workspace,
            before.ownerId(), projectId, MAX_DRAFTS);
        List<Summary> summaries = rows.stream().filter(id -> available(before, workspace, id))
            .map(id -> head(id, false).map(row -> summary(id, before, row)))
            .flatMap(Optional::stream)
            .toList();
        requireSameScope(principal, projectId, before);
        return new Items<>(
            summaries.stream().filter(item -> available(before, workspace, item.id()))
                .toList());
    }

    /**
     * Reads the current authorized draft head.
     *
     * @param principal current project member session
     * @param projectId collaboration project
     * @param id shared draft
     * @return current immutable head with Markdown text
     */
    public Draft get(Principal principal, String projectId, String id) {
        PublicationScope access = requireDraft(principal, projectId, id, false);
        StoredRevision head = current(id, true);
        requireSameDraft(principal, projectId, id, access);
        return draft(id, access, head);
    }

    /**
     * Appends one editor revision only if the provided base still heads the draft.
     *
     * @param principal current editing session
     * @param projectId collaboration project
     * @param id shared draft
     * @param baseRevisionId caller-observed head
     * @param write explicitly submitted version
     * @return newly written head
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Draft revise(Principal principal, String projectId, String id, String baseRevisionId,
        Write write) {
        Write checked = validate(write);
        PublicationScope scope = requireWritable(principal, projectId, id, baseRevisionId);
        int next = nextVersion(id, baseRevisionId);
        return updateHead(principal, scope, id, next, checked, null);
    }

    /**
     * Copies an authorized earlier revision into a new head under the same CAS rule.
     *
     * @param principal current editing session
     * @param projectId collaboration project
     * @param id shared draft
     * @param baseRevisionId caller-observed head
     * @param restoredId earlier authorized revision
     * @return newly appended rollback revision
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Draft rollback(Principal principal, String projectId, String id, String baseRevisionId,
        String restoredId) {
        AssistantProjectCollaboration.requireId(restoredId);
        PublicationScope scope = requireWritable(principal, projectId, id, baseRevisionId);
        int next = nextVersion(id, baseRevisionId);
        StoredRevision previous = revision(id, restoredId);
        return updateHead(principal, scope, id, next,
            new Write(previous.title(), previous.content()),
            previous.id());
    }

    /**
     * Lists the bounded revision history and scoped authorship.
     *
     * @param principal current project member session
     * @param projectId collaboration project
     * @param id shared draft
     * @return authorized revision history without Markdown bodies
     */
    public Items<RevisionSummary> history(Principal principal, String projectId, String id) {
        PublicationScope access = requireDraft(principal, projectId, id, false);
        List<StoredRevision> rows = jdbc.query("SELECT " + COLUMNS + " " + REVISIONS
            + " ORDER BY version_no DESC LIMIT ?", HEADER, id, MAX_REVISIONS);
        requireSameDraft(principal, projectId, id, access);
        return new Items<>(rows.stream().map(row -> row.summary(principal.user().id())).toList());
    }

    /**
     * Loads immutable Markdown bytes from a single authorized revision.
     *
     * @param principal current project member session
     * @param projectId collaboration project
     * @param id shared draft
     * @param revisionId exact requested revision
     * @return immutable revision and scoped authorship
     */
    public Revision getRevision(Principal principal, String projectId, String id,
        String revisionId) {
        PublicationScope access = requireDraft(principal, projectId, id, false);
        AssistantProjectCollaboration.requireId(revisionId);
        StoredRevision row = revision(id, revisionId);
        requireSameDraft(principal, projectId, id, access);
        return new Revision(row.summary(principal.user().id()), row.content());
    }

    /**
     * Durably removes a shared draft and all of its revisions.
     *
     * @param principal current project owner session
     * @param projectId collaboration project
     * @param id owned shared draft
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void delete(Principal principal, String projectId, String id) {
        PublicationScope scope = requireDraft(principal, projectId, id, true);
        scope.requireOwner();
        erasure.deleteSharedDraft(scope.ownerId(), principal.user().workspaceId(), id);
    }

    private PublicationScope requireWritable(Principal principal, String projectId, String id,
        String baseRevisionId) {
        AssistantProjectCollaboration.requireId(baseRevisionId);
        PublicationScope scope = requireDraft(principal, projectId, id, true);
        scope.requireWriter();
        return scope;
    }

    private PublicationScope requireDraft(Principal principal, String projectId, String id,
        boolean lock) {
        requireReady();
        AssistantProjectCollaboration.requireId(id);
        PublicationScope scope = collaboration.publicationScope(principal, projectId, lock);
        String workspace = principal.user().workspaceId();
        String lockClause = lock ? " FOR UPDATE" : "";
        if (jdbc.queryForList("SELECT id " + DRAFTS + " AND id = ?" + lockClause, String.class,
            workspace, scope.ownerId(), projectId, id).isEmpty()
            || !available(scope, workspace, id)) {
            throw missing();
        }
        return scope;
    }

    private boolean available(PublicationScope scope, String workspace, String id) {
        if (journal.blocksSharedDraft(scope.ownerId(), workspace, id)) {
            return false;
        }
        List<String> authors = jdbc.queryForList("SELECT DISTINCT author_id " + REVISIONS,
            String.class, id);
        return !authors.isEmpty()
            && authors.stream().noneMatch(journal::blocksCollaborationParticipant);
    }

    private void requireSameDraft(Principal principal, String projectId, String id,
        PublicationScope before) {
        if (!before.equals(requireDraft(principal, projectId, id, false))) {
            throw missing();
        }
    }

    private void requireSameScope(Principal principal, String projectId, PublicationScope before) {
        requireReady();
        if (!before.equals(collaboration.publicationScope(principal, projectId, false))) {
            throw missing();
        }
    }

    private int nextVersion(String id, String expected) {
        StoredRevision head = current(id, false);
        if (!head.id().equals(expected)) {
            throw conflict("The shared draft changed. Load its current head before saving.");
        }
        if (head.versionNumber() >= MAX_REVISIONS) {
            throw conflict("The shared draft already has 32 immutable revisions.");
        }
        return head.versionNumber() + 1;
    }

    private Draft updateHead(Principal principal, PublicationScope scope, String id, int next,
        Write write, String restoredFrom) {
        String workspace = principal.user().workspaceId();
        if (!available(scope, workspace, id)) {
            throw missing();
        }
        long now = System.currentTimeMillis();
        StoredRevision revision = append(id, principal.user().id(), next, write, restoredFrom, now);
        jdbc.update("UPDATE assistant_project_shared_draft SET updated_at = ? WHERE id = ?", now,
            id);
        return draft(id, scope, revision);
    }

    private StoredRevision append(String id, String author, int number, Write write,
        String restoredFrom, long now) {
        String revision = UUID.randomUUID().toString();
        jdbc.update(
            """
                INSERT INTO assistant_project_shared_draft_revision
                    (id, draft_id, version_no, author_id, title, content, restored_from_revision_id, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
            revision, id, number, author, write.title(), write.content(), restoredFrom, now);
        return new StoredRevision(revision, number, author, write.title(), write.content(),
            restoredFrom, Instant.ofEpochMilli(now));
    }

    private StoredRevision current(String id, boolean includeBody) {
        return head(id, includeBody).orElseThrow(AssistantSharedDrafts::missing);
    }

    private Optional<StoredRevision> head(String id, boolean includeBody) {
        String columns = includeBody ? COLUMNS + ", content " : COLUMNS + " ";
        return jdbc.query("SELECT " + columns + REVISIONS + " ORDER BY version_no DESC LIMIT 1",
            includeBody ? BODY : HEADER, id).stream().findFirst();
    }

    private StoredRevision revision(String draft, String id) {
        return jdbc.query("SELECT " + COLUMNS + ", content " + REVISIONS + " AND id = ?", BODY,
            draft, id).stream().findFirst().orElseThrow(AssistantSharedDrafts::missing);
    }

    private void requireReady() {
        if (!enabled || !journal.enabled()) {
            throw new AssistantApiException(HttpStatus.FORBIDDEN, "CAPABILITY_UNAVAILABLE",
                "Shared project drafts are unavailable.");
        }
        if (!journal.acceptsErasure()) {
            throw new AssistantApiException(HttpStatus.SERVICE_UNAVAILABLE,
                "CAPABILITY_UNAVAILABLE",
                "Shared draft revocation is temporarily unavailable.");
        }
    }

    private static Write validate(Write input) {
        if (input == null || !validText(input.title(), 120, false) || input.title().isBlank()
            || input.title().codePointCount(0, input.title().length()) > 120
            || !validText(input.content(), MAX_BODY_BYTES, true)
            || input.content().getBytes(StandardCharsets.UTF_8).length > MAX_BODY_BYTES) {
            throw invalid("Provide a title of up to 120 characters and Markdown of up to 32 KiB.");
        }
        return new Write(input.title().strip(), input.content());
    }

    private static boolean validText(String text, int maximum, boolean multiline) {
        return text != null && text.length() <= maximum * 2
            && StandardCharsets.UTF_8.newEncoder().canEncode(text)
            && text.codePoints().noneMatch(point -> Character.isISOControl(point)
                && (!multiline || (point != '\n' && point != '\r' && point != '\t')));
    }

    private static StoredRevision readHeader(ResultSet row, int index) throws SQLException {
        return read(row, null);
    }

    private static StoredRevision readBody(ResultSet row, int index) throws SQLException {
        return read(row, row.getString("content"));
    }

    private static StoredRevision read(ResultSet row, String content) throws SQLException {
        return new StoredRevision(row.getString("id"), row.getInt("version_no"),
            row.getString("author_id"), row.getString("title"), content,
            row.getString("restored_from_revision_id"),
            Instant.ofEpochMilli(row.getLong("created_at")));
    }

    private static Summary summary(String id, PublicationScope access, StoredRevision head) {
        return new Summary(id, head.title(), head.id(), head.versionNumber(), head.createdAt(),
            access.ownsProject() || access.editsProject());
    }

    private static Draft draft(String id, PublicationScope access, StoredRevision head) {
        return new Draft(summary(id, access, head), head.content(), RETENTION);
    }

    private static AssistantApiException missing() {
        return new AssistantApiException(HttpStatus.NOT_FOUND, "NOT_FOUND",
            "Shared draft was not found.");
    }

    private static AssistantApiException invalid(String message) {
        return new AssistantApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message);
    }

    private static AssistantApiException conflict(String message) {
        return new AssistantApiException(HttpStatus.CONFLICT, "CONFLICT", message);
    }

    /**
     * Explicitly entered Markdown and title.
     *
     * @param title visible draft title
     * @param content original Markdown
     */
    public record Write(String title, String content) {

        @Override
        public String toString() {
            return "Write[protected]";
        }
    }

    /**
     * Current authorized draft heading.
     *
     * @param id shared draft
     * @param title visible title
     * @param revisionId current head revision
     * @param versionNumber current revision ordinal
     * @param updatedAt current head time
     * @param canEdit current owner or editor
     */
    public record Summary(String id, String title, String revisionId, int versionNumber,
        Instant updatedAt, boolean canEdit) {
    }

    /**
     * Current draft and its explicit shared work retention policy.
     *
     * @param summary current shared heading
     * @param content exact Markdown body
     * @param retentionPolicy applicable shared draft contributor erasure
     */
    public record Draft(@JsonUnwrapped Summary summary, String content, String retentionPolicy) {

        @Override
        public String toString() {
            return "Draft[protected]";
        }
    }

    /**
     * Immutable version information and restricted author provenance.
     *
     * @param id shared revision identifier
     * @param title saved title
     * @param versionNumber immutable revision ordinal
     * @param createdAt creation time
     * @param authoredByMe whether the requesting actor authored the revision
     * @param restoredFromRevisionId original version for a rollback
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RevisionSummary(String id, String title, int versionNumber, Instant createdAt,
        boolean authoredByMe, String restoredFromRevisionId) {
    }

    /**
     * Immutable authorized Markdown revision.
     *
     * @param summary immutable revision metadata
     * @param content exact stored Markdown
     */
    public record Revision(@JsonUnwrapped RevisionSummary summary, String content) {

        @Override
        public String toString() {
            return "Revision[protected]";
        }
    }

    private record StoredRevision(String id, int versionNumber, String authorId, String title,
        String content, String restoredFrom, Instant createdAt) {

        private RevisionSummary summary(String actor) {
            return new RevisionSummary(id, title, versionNumber, createdAt, authorId.equals(actor),
                restoredFrom);
        }
    }
}

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
import top.continew.admin.assistant.privacy.AssistantDeletionJournal;
import top.continew.admin.assistant.privacy.AssistantErasure;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Grants roles on explicitly contributed project text within a workspace.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
@Service
@ConditionalOnProperty(name = "assistant.enabled", havingValue = "true")
public class AssistantProjectCollaboration {

    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{2,31}");
    private static final int MAX_TEXT_BYTES = 16 * 1024;
    private static final int MAX_MATERIALS = 64;
    private static final int MAX_GRANTS = 32;
    private static final int MAX_RECEIVED = 64;
    private static final int MAX_DAILY_INVITES = 128;
    private static final String PENDING = "pending";
    private static final String ACCEPTED = "accepted";
    private static final String REVOKED = "revoked";
    private static final String OWNER = "owner";
    private static final String EDITOR = "editor";
    private static final String VIEWER = "viewer";
    private static final String ACTIVE =
        " AND (g.state = 'accepted' OR (g.state = 'pending' AND g.expires_at > ?))";
    private static final String GRANTS = """
        SELECT g.id, g.project_id, p.name AS project_name, g.owner_id, g.member_id, a.username,
            g.role, g.state, g.created_at, g.expires_at, g.accepted_at,
            o.deleted_at IS NULL AND om.account_id IS NOT NULL
                AND mm.account_id IS NOT NULL AS current_membership
        FROM assistant_project_collab_grant g
        JOIN assistant_project p ON p.id = g.project_id AND p.account_id = g.owner_id
            AND p.workspace_id = g.workspace_id AND p.deleted_at IS NULL
        JOIN assistant_account o ON o.id = g.owner_id
        JOIN assistant_account a ON a.id = g.member_id AND a.deleted_at IS NULL
        LEFT JOIN assistant_workspace_member om
            ON om.workspace_id = g.workspace_id AND om.account_id = g.owner_id
        LEFT JOIN assistant_workspace_member mm
            ON mm.workspace_id = g.workspace_id AND mm.account_id = g.member_id
        """;
    private static final String SCOPE =
        " g.workspace_id = ? AND g.owner_id = ? AND g.project_id = ?";
    private static final String TEXTS =
        """
             FROM assistant_project_collab_text WHERE workspace_id = ? AND owner_id = ? AND project_id = ?
            """;
    private static final RowMapper<Grant> GRANT_ROW = AssistantProjectCollaboration::readGrant;
    private static final RowMapper<TextRow> TEXT_ROW = (row, index) -> new TextRow(
        row.getString("id"), row.getString("author_id"), row.getString("name"),
        Instant.ofEpochMilli(row.getLong("created_at")));

    private final JdbcTemplate jdbc;
    private final AssistantAccounts accounts;
    private final AssistantProjects projects;
    private final AssistantDeletionJournal journal;
    private final AssistantErasure erasure;
    private final boolean enabled;

    /**
     * Connects text collaboration to current workspace scope and durable erasure.
     *
     * @param jdbc scoped data
     * @param accounts account lifecycle
     * @param projects private project ownership
     * @param journal durable removal
     * @param erasure transactional replay
     * @param enabled opt-in flag
     */
    public AssistantProjectCollaboration(JdbcTemplate jdbc, AssistantAccounts accounts,
        AssistantProjects projects, AssistantDeletionJournal journal, AssistantErasure erasure,
        @Value("${assistant.project-collaboration.enabled:false}") boolean enabled) {
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.projects = projects;
        this.journal = journal;
        this.erasure = erasure;
        this.enabled = enabled;
    }

    /**
     * Issues a role invitation to one current workspace member.
     *
     * @param principal consumer session and selected workspace
     * @param projectId owned project
     * @param username current workspace username
     * @param role viewer or editor
     * @return pending invitation
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Invitation invite(Principal principal, String projectId, String username, String role) {
        requireReady();
        requireId(projectId);
        if (username == null || !USERNAME.matcher(username).matches()
            || (!EDITOR.equals(role) && !VIEWER.equals(role))) {
            throw invalid("Enter an existing workspace username and a viewer or editor role.");
        }
        User owner = principal.user();
        projects.requireOwned(owner, projectId);
        String canonicalUsername = username.toLowerCase(Locale.ROOT);
        String member = findMember(owner.workspaceId(), canonicalUsername);
        if (member.equals(owner.id())) {
            throw invalid("The project owner already has access.");
        }
        lockAccounts(owner.id(), member);
        requireSession(principal, true);
        requireMember(owner.workspaceId(), member, true);
        if (!member.equals(findMember(owner.workspaceId(), canonicalUsername))) {
            throw missing();
        }
        projects.requireOwned(owner, projectId);
        long now = System.currentTimeMillis();
        boolean duplicate = findGrants(SCOPE + " AND g.member_id = ?" + ACTIVE,
            owner.workspaceId(), owner.id(), projectId, member, now).stream()
            .anyMatch(grant -> grantActive(grant, owner.workspaceId()));
        if (duplicate) {
            throw conflict("The member already has a current invitation or role.");
        }
        enforceInviteQuota(owner, projectId, member, now);
        String id = UUID.randomUUID().toString();
        jdbc.update(
            """
                INSERT INTO assistant_project_collab_grant
                    (id, workspace_id, owner_id, project_id, member_id, role, state, created_at, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, 'pending', ?, ?)
                """,
            id, owner.workspaceId(), owner.id(), projectId, member, role, now,
            now + Duration.ofDays(7).toMillis());
        return ownerGrant(owner, projectId, id).view(true);
    }

    /**
     * Lists current invitations and roles for an owned project.
     *
     * @param principal consumer session and selected workspace
     * @param projectId owned project
     * @return owner-visible invitations
     */
    public Items<Invitation> ownerInvitations(Principal principal, String projectId) {
        requireReady();
        User owner = principal.user();
        requireSession(principal, false);
        projects.requireOwned(owner, projectId);
        List<Grant> rows =
            findGrants(SCOPE + ACTIVE + " ORDER BY g.created_at DESC, g.id DESC LIMIT ?",
                owner.workspaceId(), owner.id(), projectId, System.currentTimeMillis(), MAX_GRANTS);
        requireSession(principal, false);
        projects.requireOwned(owner, projectId);
        return new Items<>(rows.stream().filter(row -> grantActive(row, owner.workspaceId()))
            .map(row -> row.view(true)).toList());
    }

    /**
     * Permanently retires an owner's invitation or accepted role.
     *
     * @param principal owner session and selected workspace
     * @param projectId owned project
     * @param id targeted invitation or role
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void revoke(Principal principal, String projectId, String id) {
        requireReady();
        requireId(projectId);
        requireId(id);
        User owner = principal.user();
        // Every project grant mutation takes the same owner lock, including recipient actions.
        accounts.lockActive(owner.id());
        requireSession(principal, true);
        retire(owner.workspaceId(), ownerGrant(owner, projectId, id));
    }

    /**
     * Lists live invitations assigned to this recipient.
     *
     * @param principal recipient session and selected workspace
     * @return active inbox
     */
    public Items<Invitation> invitations(Principal principal) {
        return new Items<>(received(principal, PENDING).stream().map(row -> row.view(false))
            .toList());
    }

    /**
     * Accepts the authenticated recipient's explicitly confirmed invitation.
     *
     * @param principal recipient session and selected workspace
     * @param id invitation addressed to the recipient
     * @param confirm affirmative consent
     * @return accepted invitation
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Invitation accept(Principal principal, String id, boolean confirm) {
        requireReady();
        requireId(id);
        if (!confirm) {
            throw invalid("Confirm the project invitation before accepting.");
        }
        User user = principal.user();
        Grant grant = lockReceived(principal, id);
        if (!grantActive(grant, user.workspaceId())) {
            throw missing();
        }
        if (PENDING.equals(grant.state())) {
            long now = System.currentTimeMillis();
            if (jdbc.update("""
                UPDATE assistant_project_collab_grant SET state = 'accepted', accepted_at = ?
                WHERE id = ? AND workspace_id = ? AND member_id = ? AND state = 'pending'
                    AND expires_at > ?
                """, now, id, user.workspaceId(), user.id(), now) != 1) {
                throw missing();
            }
            grant = recipientGrant(user, id);
        }
        return grant.view(false);
    }

    /**
     * Permanently declines an invitation that has not been accepted.
     *
     * @param principal recipient session and selected workspace
     * @param id invitation addressed to the recipient
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void decline(Principal principal, String id) {
        requireReady();
        requireId(id);
        Grant grant = lockReceived(principal, id);
        if (ACCEPTED.equals(grant.state())) {
            throw missing();
        }
        retire(principal.user().workspaceId(), grant);
    }

    /**
     * Lists accepted projects and roles in the selected workspace.
     *
     * @param principal recipient session and selected workspace
     * @return accepted collaboration scopes
     */
    public Items<SharedProject> sharedProjects(Principal principal) {
        return new Items<>(received(principal, ACCEPTED).stream()
            .map(row -> new SharedProject(row.projectId(), row.projectName(), row.role()))
            .toList());
    }

    /**
     * Removes the current member's accepted role.
     *
     * @param principal member session and selected workspace
     * @param projectId project with an accepted role
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void leave(Principal principal, String projectId) {
        requireReady();
        Grant grant = access(principal, projectId, true).grant();
        if (grant == null) {
            throw missing();
        }
        retire(principal.user().workspaceId(), grant);
    }

    /**
     * Lists headings for explicitly contributed text.
     *
     * @param principal authorized session and selected workspace
     * @param projectId collaboration project
     * @return visible material summaries
     */
    public Items<MaterialSummary> listMaterials(Principal principal, String projectId) {
        requireReady();
        Access access = access(principal, projectId, false);
        User user = principal.user();
        List<TextRow> rows = jdbc.query("SELECT id, author_id, name, created_at" + TEXTS
            + " ORDER BY created_at DESC, id DESC LIMIT ?", TEXT_ROW, user.workspaceId(),
            access.ownerId(), projectId, MAX_MATERIALS);
        recheck(principal, projectId, access);
        return new Items<>(rows.stream().filter(row -> visible(user, access, row))
            .map(row -> row.summary(canDelete(user, access, row))).toList());
    }

    /**
     * Returns the original plain text to an authorized reader.
     *
     * @param principal authorized session and selected workspace
     * @param projectId collaboration project
     * @param id shared material identifier
     * @return authorized original text
     */
    public Material getMaterial(Principal principal, String projectId, String id) {
        requireReady();
        requireId(id);
        Access access = access(principal, projectId, false);
        User user = principal.user();
        TextRow row = text(user.workspaceId(), access.ownerId(), projectId, id);
        if (!visible(user, access, row)) {
            throw missing();
        }
        String content = jdbc.query("SELECT content" + TEXTS + " AND id = ?",
            (result, index) -> result.getString("content"), user.workspaceId(), access.ownerId(),
            projectId, id).stream().findFirst().orElseThrow(AssistantProjectCollaboration::missing);
        recheck(principal, projectId, access);
        if (!visible(user, access, row)) {
            throw missing();
        }
        return new Material(row.id(), row.name(), content, row.createdAt(),
            canDelete(user, access, row));
    }

    /**
     * Contributes expressly submitted text to the collaboration scope.
     *
     * @param principal owner or editor session and selected workspace
     * @param projectId collaboration project
     * @param name display name
     * @param content explicit plain text
     * @return created material
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Material createMaterial(Principal principal, String projectId, String name,
        String content) {
        requireReady();
        if (!bounded(name, 120, false) || !bounded(content, MAX_TEXT_BYTES, true)
            || content.getBytes(StandardCharsets.UTF_8).length > MAX_TEXT_BYTES) {
            throw invalid("Provide a name of up to 120 characters and up to 16 KiB of plain text.");
        }
        Access access = access(principal, projectId, true);
        User user = principal.user();
        if (VIEWER.equals(access.role())) {
            throw forbidden();
        }
        if (count("SELECT COUNT(*)" + TEXTS, user.workspaceId(), access.ownerId(),
            projectId) >= MAX_MATERIALS) {
            throw conflict("The project has reached its shared text limit.");
        }
        String id = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        String title = name.strip();
        jdbc.update("""
            INSERT INTO assistant_project_collab_text
                (id, workspace_id, owner_id, project_id, author_id, name, content, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """, id, user.workspaceId(), access.ownerId(), projectId, user.id(), title, content,
            now);
        return new Material(id, title, content, Instant.ofEpochMilli(now), true);
    }

    /**
     * Erases text when the actor is its editor author or the project owner.
     *
     * @param principal owner or editor session and selected workspace
     * @param projectId collaboration project
     * @param id shared material identifier
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void deleteMaterial(Principal principal, String projectId, String id) {
        requireReady();
        requireId(id);
        Access access = access(principal, projectId, true);
        User user = principal.user();
        TextRow row = text(user.workspaceId(), access.ownerId(), projectId, id);
        if (!canDelete(user, access, row)) {
            throw forbidden();
        }
        erasure.deleteCollaborationText(access.ownerId(), user.workspaceId(), id);
    }

    PublicationScope publicationScope(Principal principal, String projectId, boolean lock) {
        requireReady();
        Access current = access(principal, projectId, lock);
        return new PublicationScope(current.ownerId(), current.role(), current.grantId());
    }

    private List<Grant> received(Principal principal, String state) {
        requireReady();
        requireSession(principal, false);
        User user = principal.user();
        List<Grant> rows = findGrants(" g.workspace_id = ? AND g.member_id = ? AND g.state = ?"
            + ACTIVE + " ORDER BY g.created_at DESC, g.id DESC LIMIT ?",
            user.workspaceId(), user.id(), state, System.currentTimeMillis(), MAX_RECEIVED);
        requireSession(principal, false);
        return rows.stream().filter(row -> grantActive(row, user.workspaceId())).toList();
    }

    private Access access(Principal principal, String projectId, boolean lock) {
        requireId(projectId);
        User actor = principal.user();
        String workspace = actor.workspaceId();
        String owner = jdbc.queryForList("""
            SELECT account_id FROM assistant_project
            WHERE workspace_id = ? AND id = ? AND deleted_at IS NULL
            """, String.class, workspace, projectId).stream().findFirst()
            .orElseThrow(AssistantProjectCollaboration::missing);
        if (lock) {
            lockAccounts(owner, actor.id());
        }
        requireSession(principal, lock);
        requireMember(workspace, owner, lock);
        if (owner.equals(actor.id())) {
            projects.requireOwned(actor, projectId);
            return new Access(owner, OWNER, null);
        }
        Grant grant = findGrants(SCOPE + " AND g.member_id = ? AND g.state = 'accepted'",
            workspace, owner, projectId, actor.id()).stream()
            .filter(row -> grantActive(row, workspace)).findFirst()
            .orElseThrow(AssistantProjectCollaboration::missing);
        return new Access(owner, grant.role(), grant);
    }

    private void recheck(Principal principal, String projectId, Access before) {
        requireReady();
        Access current = access(principal, projectId, false);
        if (!before.ownerId().equals(current.ownerId()) || !before.role().equals(current.role())
            || !Objects.equals(before.grantId(), current.grantId())) {
            throw missing();
        }
    }

    private Grant lockReceived(Principal principal, String id) {
        User actor = principal.user();
        Grant grant = recipientGrant(actor, id);
        lockAccounts(grant.ownerId(), actor.id());
        requireSession(principal, true);
        requireMember(actor.workspaceId(), grant.ownerId(), true);
        return recipientGrant(actor, id);
    }

    private Grant ownerGrant(User owner, String projectId, String id) {
        return findGrants(SCOPE + " AND g.id = ?", owner.workspaceId(), owner.id(),
            projectId, id).stream().findFirst().orElseThrow(AssistantProjectCollaboration::missing);
    }

    private Grant recipientGrant(User user, String id) {
        return findGrants(" g.workspace_id = ? AND g.member_id = ? AND g.id = ?",
            user.workspaceId(), user.id(), id).stream().findFirst()
            .orElseThrow(AssistantProjectCollaboration::missing);
    }

    private List<Grant> findGrants(String conditions, Object... args) {
        return jdbc.query(GRANTS + " WHERE" + conditions, GRANT_ROW, args);
    }

    private boolean grantActive(Grant row, String workspace) {
        return row.currentMembership() && (ACCEPTED.equals(row.state())
            || (PENDING.equals(row.state()) && row.expiresAt().isAfter(Instant.now())))
            && !journal.blocksCollaborationGrant(row.ownerId(), workspace, row.id())
            && !journal.blocksCollaborationParticipant(row.memberId());
    }

    private void retire(String workspace, Grant grant) {
        if (!REVOKED.equals(grant.state())) {
            erasure.revokeCollaborationGrant(grant.ownerId(), workspace, grant.id());
        }
    }

    private TextRow text(String workspace, String owner, String projectId, String id) {
        return jdbc.query("SELECT id, author_id, name, created_at" + TEXTS + " AND id = ?",
            TEXT_ROW, workspace, owner, projectId, id).stream().findFirst()
            .orElseThrow(AssistantProjectCollaboration::missing);
    }

    private boolean visible(User user, Access access, TextRow row) {
        return !journal.blocksCollaborationParticipant(row.authorId())
            && !journal.blocksCollaborationText(access.ownerId(), user.workspaceId(), row.id());
    }

    private static boolean canDelete(User user, Access access, TextRow row) {
        return OWNER.equals(access.role())
            || (EDITOR.equals(access.role()) && user.id().equals(row.authorId()));
    }

    private void enforceInviteQuota(User owner, String projectId, String member, long now) {
        String countGrants = "SELECT COUNT(*) FROM assistant_project_collab_grant g WHERE";
        if (count(countGrants + SCOPE + ACTIVE, owner.workspaceId(), owner.id(), projectId,
            now) >= MAX_GRANTS
            || count(countGrants + " g.member_id = ? AND g.workspace_id = ?" + ACTIVE,
                member, owner.workspaceId(), now) >= MAX_RECEIVED) {
            throw conflict("The project or recipient has reached its collaboration limit.");
        }
        if (count(countGrants + " g.owner_id = ? AND g.created_at > ?", owner.id(),
            now - Duration.ofDays(1).toMillis()) >= MAX_DAILY_INVITES) {
            throw new AssistantApiException(HttpStatus.TOO_MANY_REQUESTS, "COLLABORATION_LIMIT",
                "The daily project invitation limit was reached.");
        }
    }

    private int count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    private String findMember(String workspace, String username) {
        return jdbc
            .queryForList(
                """
                    SELECT a.id FROM assistant_account a JOIN assistant_workspace_member m ON m.account_id = a.id
                    WHERE a.username = ? AND m.workspace_id = ? AND a.deleted_at IS NULL
                    """,
                String.class, username, workspace)
            .stream()
            .filter(id -> !journal.blocksCollaborationParticipant(id))
            .findFirst().orElseThrow(AssistantProjectCollaboration::missing);
    }

    private void lockAccounts(String owner, String actor) {
        try {
            if (owner.compareTo(actor) <= 0) {
                accounts.lockActive(owner);
                if (!owner.equals(actor)) {
                    accounts.lockActive(actor);
                }
            } else {
                accounts.lockActive(actor);
                accounts.lockActive(owner);
            }
        } catch (AssistantApiException error) {
            if (HttpStatus.UNAUTHORIZED.equals(error.status())) {
                throw missing();
            }
            throw error;
        }
    }

    private void requireSession(Principal principal, boolean lock) {
        User user = principal.user();
        String sql = """
            SELECT 1 FROM assistant_workspace_member m
            JOIN assistant_account a ON a.id = m.account_id AND a.deleted_at IS NULL
            JOIN assistant_session s ON s.account_id = m.account_id
            WHERE m.account_id = ? AND m.workspace_id = ? AND s.id = ? AND s.expires_at > ?
            """ + (lock ? " FOR UPDATE" : "");
        if (journal.blocksCollaborationParticipant(user.id())
            || jdbc.queryForList(sql, Integer.class, user.id(), user.workspaceId(),
                principal.sessionId(), System.currentTimeMillis()).isEmpty()) {
            throw missing();
        }
    }

    private void requireMember(String workspace, String account, boolean lock) {
        String sql = """
            SELECT 1 FROM assistant_workspace_member m
            JOIN assistant_account a ON a.id = m.account_id AND a.deleted_at IS NULL
            WHERE m.workspace_id = ? AND m.account_id = ?
            """ + (lock ? " FOR UPDATE" : "");
        if (journal.blocksCollaborationParticipant(account)
            || jdbc.queryForList(sql, Integer.class, workspace, account).isEmpty()) {
            throw missing();
        }
    }

    private void requireReady() {
        if (!enabled || !journal.enabled()) {
            throw new AssistantApiException(HttpStatus.FORBIDDEN, "CAPABILITY_UNAVAILABLE",
                "Project collaboration is unavailable.");
        }
        if (!journal.acceptsErasure()) {
            throw new AssistantApiException(HttpStatus.SERVICE_UNAVAILABLE,
                "CAPABILITY_UNAVAILABLE",
                "Project collaboration revocation is temporarily unavailable.");
        }
    }

    private static boolean bounded(String value, int maximum, boolean multiline) {
        return value != null && value.length() <= maximum * 2 && !value.isBlank()
            && value.codePointCount(0, value.length()) <= maximum
            && StandardCharsets.UTF_8.newEncoder().canEncode(value)
            && value.codePoints().noneMatch(point -> Character.isISOControl(point)
                && (!multiline || (point != '\n' && point != '\r' && point != '\t')));
    }

    static void requireId(String id) {
        try {
            if (id != null && id.length() == 36 && UUID.fromString(id).toString().equals(id)) {
                return;
            }
        } catch (IllegalArgumentException error) {
            throw invalid("Invalid collaboration identifier.");
        }
        throw invalid("Invalid collaboration identifier.");
    }

    private static AssistantApiException invalid(String reason) {
        return new AssistantApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", reason);
    }

    private static AssistantApiException missing() {
        return new AssistantApiException(HttpStatus.NOT_FOUND, "NOT_FOUND",
            "Project collaboration item was not found.");
    }

    private static AssistantApiException forbidden() {
        return new AssistantApiException(HttpStatus.FORBIDDEN, "FORBIDDEN",
            "The project role does not permit that change.");
    }

    private static AssistantApiException conflict(String reason) {
        return new AssistantApiException(HttpStatus.CONFLICT, "CONFLICT", reason);
    }

    private static Grant readGrant(ResultSet row, int index) throws SQLException {
        long acceptedAt = row.getLong("accepted_at");
        return new Grant(row.getString("id"), row.getString("project_id"),
            row.getString("project_name"), row.getString("owner_id"), row.getString("member_id"),
            row.getString("username"), row.getString("role"), row.getString("state"),
            Instant.ofEpochMilli(row.getLong("created_at")),
            Instant.ofEpochMilli(row.getLong("expires_at")),
            acceptedAt == 0 ? null : Instant.ofEpochMilli(acceptedAt),
            row.getBoolean("current_membership"));
    }

    /**
     * Bounded collaboration results.
     *
     * @param items selected results
     * @param <T> list item type
     */
    public record Items<T>(List<T> items) {
    }

    /**
     * Role invitation with recipient fields restricted to the owner.
     *
     * @param id invitation or grant identifier
     * @param projectId collaboration project
     * @param projectName approved project label
     * @param role viewer or editor
     * @param state pending or accepted
     * @param createdAt time of invitation
     * @param expiresAt pending acceptance deadline
     * @param acceptedAt acceptance time if applicable
     * @param recipientUsername recipient username for owner only
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Invitation(String id, String projectId, String projectName, String role,
        String state, Instant createdAt, Instant expiresAt, Instant acceptedAt,
        String recipientUsername) {
    }

    /**
     * Minimal accepted collaboration identity.
     *
     * @param id collaboration project identifier
     * @param name approved project label
     * @param role viewer or editor
     */
    public record SharedProject(String id, String name, String role) {
    }

    /**
     * Heading for an explicitly shared text material.
     *
     * @param id shared material identifier
     * @param name display name
     * @param createdAt creation time
     * @param canDelete current actor may remove this item
     */
    public record MaterialSummary(String id, String name, Instant createdAt, boolean canDelete) {
    }

    /**
     * Original text visible under the current authorization.
     *
     * @param id shared material identifier
     * @param name display name
     * @param content explicitly contributed plain text
     * @param createdAt creation time
     * @param canDelete current actor may remove this item
     */
    public record Material(String id, String name, String content, Instant createdAt,
        boolean canDelete) {

        @Override
        public String toString() {
            return "Material[protected]";
        }
    }

    private record Access(String ownerId, String role, Grant grant) {

        private String grantId() {
            return grant == null ? null : grant.id();
        }
    }

    record PublicationScope(String ownerId, String role, String grantId) {

        void requireOwner() {
            if (!ownsProject()) {
                throw forbidden();
            }
        }

        void requireWriter() {
            if (!ownsProject() && !editsProject()) {
                throw forbidden();
            }
        }

        boolean ownsProject() {
            return OWNER.equals(role);
        }

        boolean editsProject() {
            return EDITOR.equals(role);
        }
    }

    private record Grant(String id, String projectId, String projectName, String ownerId,
        String memberId, String username, String role, String state, Instant createdAt,
        Instant expiresAt, Instant acceptedAt, boolean currentMembership) {

        private Invitation view(boolean owner) {
            return new Invitation(id, projectId, projectName, role, state, createdAt, expiresAt,
                acceptedAt, owner ? username : null);
        }
    }

    private record TextRow(String id, String authorId, String name, Instant createdAt) {

        private MaterialSummary summary(boolean removable) {
            return new MaterialSummary(id, name, createdAt, removable);
        }
    }
}

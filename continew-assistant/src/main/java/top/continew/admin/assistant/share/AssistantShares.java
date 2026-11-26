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

package top.continew.admin.assistant.share;

import com.fasterxml.jackson.annotation.JsonInclude;
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
import top.continew.admin.assistant.chat.AssistantConversationReadAccess;
import top.continew.admin.assistant.chat.AssistantConversations;
import top.continew.admin.assistant.chat.AssistantConversations.Conversation;
import top.continew.admin.assistant.chat.AssistantConversations.ConversationCreate;
import top.continew.admin.assistant.moderation.ModerationTarget;
import top.continew.admin.assistant.privacy.AssistantErasure;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Publishes revocable, immutable text snapshots from owned conversations.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
@Service
@ConditionalOnProperty(name = "assistant.enabled", havingValue = "true")
public class AssistantShares {

    private static final long DAY = Duration.ofDays(1).toMillis();
    private static final long LIFETIME = 7 * DAY;
    private static final int ACTIVE_LIMIT = 32;
    private static final int DAILY_LIMIT = 64;
    private static final int MESSAGE_LIMIT = 64;
    private static final int MESSAGE_LENGTH_LIMIT = 16_000;
    private static final int SNAPSHOT_LIMIT = 256 * 1024;
    private static final Pattern ID =
        Pattern.compile("[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}");
    private static final Pattern TOKEN = Pattern.compile("sh_[A-Za-z0-9_-]{43}");
    private static final Pattern REVISION = Pattern.compile("[a-f0-9]{64}");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder BASE64 = Base64.getUrlEncoder().withoutPadding();
    private static final Set<String> SCOPES = Set.of("branch", "reply");
    private static final Set<String> AUDIENCES = Set.of("link", "workspace");
    private static final String OWNER_SOURCE =
        """
            SELECT c.title, c.project_id, c.sharing_restricted, c.sharing_policy_revision, c.read_lock_revision, (
                SELECT w.personal_owner_id FROM assistant_workspace w WHERE w.id = c.workspace_id
            ) AS personal_owner_id
            FROM assistant_conversation c
            WHERE c.id = ? AND c.workspace_id = ? AND c.account_id = ? AND c.deleted_at IS NULL
                AND c.read_locked = FALSE
                AND EXISTS (SELECT 1 FROM assistant_workspace_member wm
                    WHERE wm.workspace_id = c.workspace_id AND wm.account_id = c.account_id)
            """;
    private static final String SHARE_SOURCE = """
        SELECT s.id, s.workspace_id, s.account_id, s.conversation_id, s.source_message_id,
            s.scope, s.audience, s.frozen_title, s.snapshot_digest, s.created_at, s.expires_at,
            c.sharing_policy_revision, c.read_lock_revision
        FROM assistant_share s
        JOIN assistant_conversation c ON c.id = s.conversation_id
            AND c.workspace_id = s.workspace_id AND c.account_id = s.account_id
        JOIN assistant_account a ON a.id = s.account_id
        JOIN assistant_workspace w ON w.id = s.workspace_id
        """;
    private static final String LIVE_SHARE_SOURCE = """
            AND s.revoked_at IS NULL AND s.expires_at > ?
            AND c.deleted_at IS NULL AND a.deleted_at IS NULL AND c.sharing_restricted = false
            AND c.read_locked = FALSE
            AND EXISTS (SELECT 1 FROM assistant_workspace_member wm
                WHERE wm.workspace_id = s.workspace_id AND wm.account_id = s.account_id)
            AND (s.audience = 'workspace'
                OR (s.audience = 'link' AND w.personal_owner_id = s.account_id
                    AND c.project_id IS NULL))
        """;
    private static final String TOKEN_SOURCE =
        SHARE_SOURCE + " WHERE s.token_digest = ? " + LIVE_SHARE_SOURCE;
    private static final String MODERATION_SOURCE =
        SHARE_SOURCE + " WHERE s.workspace_id = ? AND s.id = ? " + LIVE_SHARE_SOURCE;
    private static final RowMapper<Summary> SUMMARY = (row, index) -> {
        long revokedAt = row.getLong("revoked_at");
        Instant revoked = row.wasNull() ? null : Instant.ofEpochMilli(revokedAt);
        return new Summary(row.getString("id"), row.getString("scope"), row.getString("audience"),
            Instant.ofEpochMilli(row.getLong("created_at")),
            Instant.ofEpochMilli(row.getLong("expires_at")), revoked);
    };
    private static final RowMapper<StoredShare> STORED = (row, index) -> new StoredShare(
        row.getString("id"), row.getString("workspace_id"), row.getString("account_id"),
        row.getString("conversation_id"), row.getString("source_message_id"),
        row.getString("scope"), row.getString("audience"), row.getString("frozen_title"),
        row.getString("snapshot_digest"), row.getLong("created_at"),
        Instant.ofEpochMilli(row.getLong("expires_at")),
        row.getString("sharing_policy_revision"), row.getString("read_lock_revision"));

    private final JdbcTemplate jdbc;
    private final AssistantAccounts accounts;
    private final AssistantConversations conversations;
    private final AssistantErasure erasure;
    private final AssistantConversationReadAccess readAccess;

    /**
     * Connects sharing to account ownership and the independent deletion journal.
     *
     * @param jdbc assistant storage
     * @param accounts account lock and authorization
     * @param conversations owned conversations
     * @param erasure durable deletion and revocation
     * @param readAccess durable source read scope
     */
    public AssistantShares(JdbcTemplate jdbc, AssistantAccounts accounts,
        AssistantConversations conversations, AssistantErasure erasure,
        AssistantConversationReadAccess readAccess) {
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.conversations = conversations;
        this.erasure = erasure;
        this.readAccess = readAccess;
    }

    /**
     * Shows exactly the text and visibility of a prospective share.
     *
     * @param principal owner and selected workspace
     * @param conversationId owned conversation
     * @param request share selection
     * @return preview with publication revision
     */
    @Transactional(readOnly = true)
    public Preview preview(Principal principal, String conversationId, Request request) {
        requireReady();
        validate(request);
        return buildPreview(principal.user(), conversationId, request, false);
    }

    /**
     * Publishes the owner-reviewed revision and returns its one-time bearer token.
     *
     * @param principal owner and selected workspace
     * @param conversationId owned conversation
     * @param request share selection and reviewed revision
     * @return creation receipt
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Published publish(Principal principal, String conversationId, Request request) {
        requireReady();
        validate(request);
        if (request.revision() == null || !REVISION.matcher(request.revision()).matches()) {
            throw validation("Preview the share before publishing it.");
        }
        User owner = principal.user();
        accounts.lockActive(owner.id());
        Preview preview = buildPreview(owner, conversationId, request, true);
        if (!preview.revision().equals(request.revision())) {
            throw new AssistantApiException(HttpStatus.CONFLICT, "SHARE_PREVIEW_STALE",
                "The share preview changed. Preview it again before publishing.");
        }

        long now = System.currentTimeMillis();
        enforceQuota(owner.id(), now);
        String id = UUID.randomUUID().toString();
        requireVisible(owner.id(), owner.workspaceId(), conversationId, id);
        byte[] rawToken = new byte[32];
        RANDOM.nextBytes(rawToken);
        String token = "sh_" + BASE64.encodeToString(rawToken);
        long expiresAt = now + LIFETIME;
        jdbc.update("""
            INSERT INTO assistant_share (id, workspace_id, account_id, conversation_id,
                source_message_id, scope, audience, frozen_title, snapshot_digest, token_digest,
                created_at, expires_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, id, owner.workspaceId(), owner.id(), conversationId, request.sourceMessageId(),
            request.scope(), request.audience(), preview.title(), preview.revision(),
            tokenDigest(token), now, expiresAt);
        return new Published(id, request.scope(), request.audience(), Instant.ofEpochMilli(now),
            Instant.ofEpochMilli(expiresAt), token);
    }

    /**
     * Lists ownership metadata without disclosing share tokens.
     *
     * @param principal owner and selected workspace
     * @param conversationId owned conversation
     * @param cursor opaque continuation
     * @param limit bounded page size
     * @return page of active, expired and revoked shares
     */
    @Transactional(readOnly = true)
    public Page list(Principal principal, String conversationId, String cursor, int limit) {
        requireReady();
        User owner = principal.user();
        ownedSource(owner, conversationId, false);
        requireVisible(owner.id(), owner.workspaceId(), conversationId, null);
        if (limit < 1 || limit > 100) {
            throw validation("Share list limit must be between 1 and 100.");
        }
        Cursor after = Cursor.parse(cursor, conversationId);
        String sql = """
            SELECT id, scope, audience, created_at, expires_at, revoked_at FROM assistant_share
            WHERE workspace_id = ? AND account_id = ? AND conversation_id = ?
            """;
        String ordering = " ORDER BY created_at DESC, id DESC LIMIT ?";
        List<Summary> rows = after == null
            ? jdbc.query(sql + ordering, SUMMARY, owner.workspaceId(), owner.id(), conversationId,
                limit + 1)
            : jdbc.query(sql + " AND (created_at < ? OR (created_at = ? AND id < ?))" + ordering,
                SUMMARY, owner.workspaceId(), owner.id(), conversationId, after.createdAt(),
                after.createdAt(), after.id(), limit + 1);
        if (rows.size() <= limit) {
            return new Page(List.copyOf(rows), null);
        }
        Summary last = rows.get(limit - 1);
        return new Page(List.copyOf(rows.subList(0, limit)),
            Cursor.encode(last.createdAt(), last.id(), conversationId));
    }

    /**
     * Revokes an owned token durably, including when an older database is restored.
     *
     * @param principal owner and selected workspace
     * @param id share identifier
     */
    public void revoke(Principal principal, String id) {
        requireReady();
        if (!validId(id)) {
            throw notFound();
        }
        erasure.revokeShare(principal, id);
    }

    /**
     * Resolves a live token subject to its running audience and privacy checks.
     *
     * @param token bearer token
     * @param viewer current recipient, nullable for a public link
     * @return the frozen snapshot
     */
    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public View read(String token, Principal viewer) {
        String digest = tokenDigest(token);
        StoredShare share = visible(digest, viewer);
        List<SharedMessage> messages = frozenMessages(share);
        visible(digest, viewer);
        return new View(share.title(), share.scope(), share.audience(), false, messages,
            share.expiresAt());
    }

    /**
     * Resolves an exact visible frozen reply to an authenticated consumer reporting it.
     *
     * @param viewer current authenticated consumer and selected workspace
     * @param token bearer supplied only in the JSON request
     * @param index zero-based index in the rendered messages
     * @return scoped transient evidence
     */
    public ModerationTarget reportTargetForViewer(Principal viewer, String token, int index) {
        String digest = tokenDigest(token);
        ModerationTarget target = reportedReply(visible(digest, viewer), index);
        visible(digest, viewer);
        return target;
    }

    /**
     * Resolves stored report evidence to a reviewer already authorized for its source workspace.
     *
     * @param workspaceId granted source workspace
     * @param shareId exact immutable share ID from the case
     * @param index exact frozen reply index from the case
     * @return still-visible transient evidence
     */
    public ModerationTarget reportTargetForOperator(String workspaceId, String shareId,
        int index) {
        StoredShare share = reportSource(workspaceId, shareId);
        ModerationTarget target = reportedReply(share, index);
        reportSource(workspaceId, shareId);
        return target;
    }

    /**
     * Withdraws only the validated origin link from an already authorized moderation decision.
     *
     * @param target scoped exact share evidence
     */
    public void withdrawReported(ModerationTarget target) {
        erasure.revokeReportedShare(target.ownerId(), target.workspaceId(), target.targetId());
    }

    private StoredShare reportSource(String workspaceId, String shareId) {
        StoredShare share = jdbc.query(MODERATION_SOURCE, STORED, workspaceId, shareId,
            System.currentTimeMillis()).stream().findFirst().orElseThrow(AssistantShares::notFound);
        requireVisibleSource(share);
        return share;
    }

    private ModerationTarget reportedReply(StoredShare share, int index) {
        List<SharedMessage> messages = frozenMessages(share);
        if (index < 0 || index >= messages.size()
            || !"assistant".equals(messages.get(index).role())) {
            throw notFound();
        }
        return new ModerationTarget(share.accountId(), share.workspaceId(), share.id(),
            share.conversationId(), share.createdAtMillis(), messages.get(index).content());
    }

    /**
     * Copies available text into an independent conversation in the recipient's workspace.
     *
     * @param token bearer token
     * @param viewer authenticated recipient and destination workspace
     * @return first or previously copied conversation
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Conversation copy(String token, Principal viewer) {
        String digest = tokenDigest(token);
        StoredShare share = visible(digest, viewer);
        User recipient = viewer.user();
        lockAccounts(share.accountId(), recipient.id());
        share = visible(digest, viewer);
        if (!activeRecipient(viewer)) {
            throw notFound();
        }
        var prior = jdbc.queryForList("""
            SELECT conversation_id FROM assistant_share_copy
            WHERE share_id = ? AND workspace_id = ? AND account_id = ?
            """, String.class, share.id(), recipient.workspaceId(), recipient.id());
        if (!prior.isEmpty()) {
            return conversations.get(recipient, prior.get(0));
        }

        List<SharedMessage> messages = frozenMessages(share);
        requireVisible(share.accountId(), share.workspaceId(), share.conversationId(), share.id());
        Conversation copy =
            conversations.create(recipient, new ConversationCreate(share.title(), null));
        insertMessages(copy, messages);
        jdbc.update("""
            INSERT INTO assistant_share_copy (share_id, workspace_id, account_id, conversation_id)
            VALUES (?, ?, ?, ?)
            """, share.id(), recipient.workspaceId(), recipient.id(), copy.id());
        return copy;
    }

    private Preview buildPreview(User owner, String conversationId, Request request,
        boolean lockSource) {
        OwnedSource source = ownedSource(owner, conversationId, lockSource);
        requireVisible(owner.id(), owner.workspaceId(), conversationId, null);
        if (source.sharingRestricted()) {
            throw restricted();
        }
        requireShareAllowed(owner, conversationId);
        if ("link".equals(request.audience())
            && (!owner.id().equals(source.personalOwnerId()) || source.projectId() != null)) {
            throw new AssistantApiException(HttpStatus.FORBIDDEN, "SHARE_AUDIENCE_UNAVAILABLE",
                "Anyone-with-the-link sharing requires an unfiled personal conversation.");
        }
        List<SharedMessage> messages = messages(conversationId, request.sourceMessageId(), request
            .scope());
        requireVisible(owner.id(), owner.workspaceId(), conversationId, null);
        requireShareAllowed(owner, conversationId);
        requireReadPolicy(owner.id(), owner.workspaceId(), conversationId, source.readRevision());
        String revision = revision(owner.id(), owner.workspaceId(), conversationId, request
            .sourceMessageId(), source.title(), request.scope(), request.audience(),
            source.policyRevision(), source.readRevision(), messages);
        return new Preview(source.title(), request.scope(), request.audience(), false, messages,
            revision);
    }

    private OwnedSource ownedSource(User owner, String conversationId, boolean lock) {
        if (!validId(conversationId)) {
            throw notFound();
        }
        return jdbc
            .query(OWNER_SOURCE + (lock ? " FOR UPDATE" : ""), (row, index) -> new OwnedSource(
                row.getString("title"), row.getString("project_id"),
                row.getString("personal_owner_id"), row.getBoolean("sharing_restricted"),
                row.getString("sharing_policy_revision"), row.getString("read_lock_revision")),
                conversationId, owner.workspaceId(), owner.id())
            .stream().findFirst()
            .orElseThrow(AssistantShares::notFound);
    }

    private List<SharedMessage> frozenMessages(StoredShare share) {
        try {
            List<SharedMessage> messages = messages(share.conversationId(), share.sourceMessageId(),
                share.scope());
            String revision = revision(share.accountId(), share.workspaceId(),
                share.conversationId(),
                share.sourceMessageId(), share.title(), share.scope(), share.audience(),
                share.policyRevision(), share.readRevision(), messages);
            if (revision.equals(share.snapshotDigest())) {
                return messages;
            }
        } catch (AssistantApiException e) {
            // An unavailable source, including a concurrent scrub, reveals no snapshot details.
            throw notFound();
        }
        throw notFound();
    }

    private List<SharedMessage> messages(String conversationId, String sourceId, String scope) {
        List<Node> selected;
        if ("reply".equals(scope)) {
            selected = jdbc.query("""
                SELECT parent_id, role, LEFT(content, ?) AS content
                FROM assistant_chat_message WHERE conversation_id = ? AND id = ?
                """, AssistantShares::node, MESSAGE_LENGTH_LIMIT + 1, conversationId, sourceId);
        } else {
            selected = jdbc.query(
                """
                    WITH RECURSIVE ancestors (id, parent_id, depth) AS (
                        SELECT id, parent_id, 0 FROM assistant_chat_message
                        WHERE conversation_id = ? AND id = ?
                        UNION ALL
                        SELECT m.id, m.parent_id, a.depth + 1
                        FROM assistant_chat_message m JOIN ancestors a ON m.id = a.parent_id
                        WHERE m.conversation_id = ? AND a.depth < ?
                    )
                    SELECT a.parent_id, m.role, LEFT(m.content, ?) AS content
                    FROM ancestors a JOIN assistant_chat_message m ON m.id = a.id AND m.conversation_id = ?
                    ORDER BY a.depth DESC
                    """,
                AssistantShares::node, conversationId, sourceId, conversationId, MESSAGE_LIMIT,
                MESSAGE_LENGTH_LIMIT + 1, conversationId);
        }

        if (selected.isEmpty()) {
            throw notFound();
        }
        Node first = selected.get(0);
        if ("reply".equals(scope) && !"assistant".equals(first.message().role())) {
            throw validation("Reply sharing requires an assistant reply.");
        }
        if ("branch".equals(scope)
            && (selected.size() > MESSAGE_LIMIT || first.parentId() != null)) {
            throw tooLarge();
        }
        int bytes = 0;
        for (Node node : selected) {
            String content = node.message().content();
            bytes += content.getBytes(StandardCharsets.UTF_8).length;
            if (bytes > SNAPSHOT_LIMIT
                || content.codePointCount(0, content.length()) > MESSAGE_LENGTH_LIMIT) {
                throw tooLarge();
            }
        }
        return selected.stream().map(Node::message).toList();
    }

    private static Node node(ResultSet row, int index) throws SQLException {
        return new Node(row.getString("parent_id"),
            new SharedMessage(row.getString("role"), row.getString("content")));
    }

    private StoredShare visible(String digest, Principal viewer) {
        if (!erasure.shareReady()) {
            throw notFound();
        }
        StoredShare share = jdbc.query(TOKEN_SOURCE, STORED, digest, System.currentTimeMillis())
            .stream().findFirst().orElseThrow(AssistantShares::notFound);
        requireVisibleSource(share);
        if ("workspace".equals(share.audience()) && (viewer == null
            || !share.workspaceId().equals(viewer.user().workspaceId())
            || !activeRecipient(viewer))) {
            throw notFound();
        }
        return share;
    }

    private void requireVisibleSource(StoredShare share) {
        requireVisible(share.accountId(), share.workspaceId(), share.conversationId(), share.id());
        requireReadPolicy(share.accountId(), share.workspaceId(), share.conversationId(),
            share.readRevision());
        if (erasure.sharingRestricted(share.accountId(), share.workspaceId(),
            share.conversationId())) {
            throw notFound();
        }
    }

    private boolean activeRecipient(Principal viewer) {
        return !jdbc.queryForList("""
            SELECT 1 FROM assistant_workspace_member wm
            JOIN assistant_account a ON a.id = wm.account_id AND a.deleted_at IS NULL
            JOIN assistant_session s ON s.account_id = a.id
            WHERE wm.workspace_id = ? AND wm.account_id = ? AND s.id = ? AND s.expires_at > ?
            """, Integer.class, viewer.user().workspaceId(), viewer.user().id(), viewer.sessionId(),
            System.currentTimeMillis()).isEmpty();
    }

    private void lockAccounts(String ownerId, String recipientId) {
        // A source and its recipient can copy each other's shares concurrently.
        boolean ownerFirst = ownerId.compareTo(recipientId) <= 0;
        try {
            accounts.lockActive(ownerFirst ? ownerId : recipientId);
            if (!ownerId.equals(recipientId)) {
                accounts.lockActive(ownerFirst ? recipientId : ownerId);
            }
        } catch (AssistantApiException e) {
            if (e.status() == HttpStatus.UNAUTHORIZED) {
                throw notFound();
            }
            throw e;
        }
    }

    private void insertMessages(Conversation copy, List<SharedMessage> messages) {
        String parentId = null;
        // Keep parents ordered before children and the final clone at the conversation timestamp.
        long timestamp = copy.updatedAt().toEpochMilli() - messages.size() + 1L;
        for (SharedMessage message : messages) {
            String id = UUID.randomUUID().toString();
            jdbc.update(
                """
                    INSERT INTO assistant_chat_message (id, conversation_id, parent_id, role, content, created_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """,
                id, copy.id(), parentId, message.role(), message.content(), timestamp++);
            parentId = id;
        }
    }

    private void enforceQuota(String accountId, long now) {
        Integer active = jdbc.queryForObject("""
            SELECT COUNT(*) FROM assistant_share
            WHERE account_id = ? AND expires_at > ? AND revoked_at IS NULL
            """, Integer.class, accountId, now);
        Integer daily = jdbc.queryForObject("""
            SELECT COUNT(*) FROM assistant_share WHERE account_id = ? AND expires_at > ?
            """, Integer.class, accountId, now + LIFETIME - DAY);
        if (active >= ACTIVE_LIMIT || daily >= DAILY_LIMIT) {
            throw new AssistantApiException(HttpStatus.TOO_MANY_REQUESTS, "SHARE_LIMIT",
                "The account has reached its sharing limit.");
        }
    }

    private void requireReady() {
        if (!erasure.shareReady()) {
            throw new AssistantApiException(HttpStatus.FORBIDDEN, "CAPABILITY_UNAVAILABLE",
                "Conversation sharing is unavailable.");
        }
    }

    private void requireVisible(String accountId, String workspaceId, String conversationId,
        String shareId) {
        if (erasure.blocksShare(accountId, workspaceId, conversationId, shareId)) {
            throw notFound();
        }
    }

    private void requireShareAllowed(User owner, String conversationId) {
        if (erasure.sharingRestricted(owner.id(), owner.workspaceId(), conversationId)) {
            throw restricted();
        }
    }

    private static void validate(Request request) {
        if (request == null || !validId(request.sourceMessageId()) || request.scope() == null
            || !SCOPES.contains(request.scope()) || request.audience() == null
            || !AUDIENCES.contains(request.audience())) {
            throw validation("Select a message, scope and audience for the share.");
        }
    }

    private static boolean validId(String value) {
        return value != null && ID.matcher(value).matches();
    }

    private static String tokenDigest(String token) {
        if (token == null || !TOKEN.matcher(token).matches()) {
            throw notFound();
        }
        return HexFormat.of().formatHex(sha256().digest(token.getBytes(StandardCharsets.UTF_8)));
    }

    private void requireReadPolicy(String accountId, String workspaceId, String conversation,
        String revision) {
        if (!readAccess.open(accountId, workspaceId, conversation, false, revision)) {
            throw notFound();
        }
    }

    private static String revision(String accountId, String workspaceId, String conversationId,
        String sourceId, String title, String scope, String audience, String policyRevision,
        String readRevision,
        List<SharedMessage> messages) {
        MessageDigest digest = sha256();
        for (String field : List.of("assistant-share-v1", accountId, workspaceId, conversationId,
            sourceId, title, scope, audience)) {
            digestField(digest, field);
        }
        if (policyRevision != null) {
            digestField(digest, policyRevision);
        }
        if (readRevision != null) {
            digestField(digest, "conversation-read");
            digestField(digest, readRevision);
        }
        for (SharedMessage message : messages) {
            digestField(digest, message.role());
            digestField(digest, message.content());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void digestField(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable.", e);
        }
    }

    static AssistantApiException notFound() {
        return new AssistantApiException(HttpStatus.NOT_FOUND, "NOT_FOUND",
            "Share source not found.");
    }

    private static AssistantApiException restricted() {
        return new AssistantApiException(HttpStatus.FORBIDDEN, "SHARE_RESTRICTED",
            "Sharing is restricted for this conversation.");
    }

    static AssistantApiException validation(String message) {
        return new AssistantApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message);
    }

    private static AssistantApiException tooLarge() {
        return new AssistantApiException(HttpStatus.PAYLOAD_TOO_LARGE, "SHARE_TOO_LARGE",
            "The selected text exceeds the share limit.");
    }

    /**
     * Scope and audience for a share preview or publication.
     *
     * @param sourceMessageId selected message
     * @param scope selected response or branch
     * @param audience selected link visibility
     * @param revision reviewed preview digest
     */
    public record Request(String sourceMessageId, String scope, String audience, String revision) {
    }

    /**
     * A single text entry without a source identifier.
     *
     * @param role message role
     * @param content shared text
     */
    public record SharedMessage(String role, String content) {
    }

    /**
     * Text and audience displayed before issuing a link.
     *
     * @param title frozen title as it would appear on publication
     * @param scope selected response or branch
     * @param audience selected link visibility
     * @param includesAttachments whether attachments are included
     * @param messages text in reading order
     * @param revision reviewed content digest
     */
    public record Preview(String title, String scope, String audience, boolean includesAttachments,
        List<SharedMessage> messages, String revision) {
    }

    /**
     * A recipient's view of the selected text.
     *
     * @param title frozen title
     * @param scope selected response or branch
     * @param audience selected link visibility
     * @param includesAttachments whether attachments are included
     * @param messages text in reading order
     * @param expiresAt expiry of the shared link
     */
    public record View(String title, String scope, String audience, boolean includesAttachments,
        List<SharedMessage> messages, Instant expiresAt) {
    }

    /**
     * Publication receipt containing the only copy of the bearer token.
     *
     * @param id share identifier
     * @param scope selected response or branch
     * @param audience selected link visibility
     * @param createdAt publication time
     * @param expiresAt expiry of the shared link
     * @param token one-time bearer
     */
    public record Published(String id, String scope, String audience, Instant createdAt,
        Instant expiresAt, String token) {

        @Override
        public String toString() {
            return "Published[id=" + id + "]";
        }
    }

    /**
     * Management metadata without a bearer token.
     *
     * @param id share identifier
     * @param scope selected response or branch
     * @param audience selected link visibility
     * @param createdAt publication time
     * @param expiresAt expiry of the shared link
     * @param revokedAt durable revocation time, when revoked
     */
    public record Summary(String id, String scope, String audience, Instant createdAt,
        Instant expiresAt, @JsonInclude(JsonInclude.Include.NON_NULL) Instant revokedAt) {
    }

    /**
     * Owner-scoped page of share metadata.
     *
     * @param items visible share metadata
     * @param nextCursor continuation or null at the end
     */
    public record Page(List<Summary> items,
        @JsonInclude(JsonInclude.Include.ALWAYS) String nextCursor) {
    }

    private record OwnedSource(String title, String projectId, String personalOwnerId,
        boolean sharingRestricted, String policyRevision, String readRevision) {
    }

    private record Node(String parentId, SharedMessage message) {
    }

    private record StoredShare(String id, String workspaceId, String accountId,
        String conversationId,
        String sourceMessageId, String scope, String audience, String title, String snapshotDigest,
        long createdAtMillis, Instant expiresAt, String policyRevision, String readRevision) {
    }

    private record Cursor(long createdAt, String id) {

        private static String encode(Instant createdAt, String id, String conversationId) {
            return BASE64
                .encodeToString((createdAt.toEpochMilli() + ":" + id + ":" + conversationId)
                    .getBytes(StandardCharsets.UTF_8));
        }

        private static Cursor parse(String cursor, String conversationId) {
            if (cursor == null) {
                return null;
            }
            try {
                if (cursor.length() > 128) {
                    throw new IllegalArgumentException();
                }
                String[] parts = new String(Base64.getUrlDecoder().decode(cursor),
                    StandardCharsets.UTF_8).split(":", -1);
                if (parts.length == 3 && validId(parts[1]) && conversationId.equals(parts[2])) {
                    return new Cursor(Long.parseLong(parts[0]), parts[1]);
                }
            } catch (IllegalArgumentException ignored) {
                // All malformed continuations have the same public error.
            }
            throw validation("Invalid share list cursor.");
        }
    }
}

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

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Stores plain-text comments on an explicitly published immutable document version.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
@Service
@ConditionalOnProperty(name = "assistant.enabled", havingValue = "true")
public class AssistantProjectArtifactComments {

    private static final int MAX_TEXT_BYTES = 4096;
    private static final int MAX_COMMENTS = 64;
    private static final String SELECT = """
        SELECT id, author_id, content, created_at
        FROM assistant_project_artifact_comment WHERE publication_id = ?
        """;
    private static final RowMapper<Row> COMMENT_ROW = (row, index) -> new Row(row.getString("id"),
        row.getString("author_id"), row.getString("content"),
        Instant.ofEpochMilli(row.getLong("created_at")));

    private final JdbcTemplate jdbc;
    private final AssistantProjectArtifactPublications publications;
    private final AssistantDeletionJournal journal;
    private final AssistantErasure erasure;
    private final boolean enabled;

    /**
     * Uses current publication authority for every comment read and write.
     *
     * @param jdbc bounded collaborative comments
     * @param publications authorization and immutable source proof
     * @param journal durable account, publication and comment revocation
     * @param erasure transactional comment removal
     * @param enabled explicit capability policy
     */
    public AssistantProjectArtifactComments(JdbcTemplate jdbc,
        AssistantProjectArtifactPublications publications, AssistantDeletionJournal journal,
        AssistantErasure erasure,
        @Value("${assistant.project-artifact-comments.enabled:false}") boolean enabled) {
        this.jdbc = jdbc;
        this.publications = publications;
        this.journal = journal;
        this.erasure = erasure;
        this.enabled = enabled;
    }

    /**
     * Lists currently visible comments on an exact publication.
     *
     * @param principal selected workspace and current session
     * @param projectId collaboration project
     * @param publicationId exact published version reference
     * @return bounded comments scoped to the selected project role
     */
    public Items<Comment> list(Principal principal, String projectId, String publicationId) {
        requireReady();
        PublicationScope before = publications.commentScope(principal, projectId, publicationId,
            false);
        List<Row> rows = jdbc.query(SELECT + " ORDER BY created_at, id LIMIT ?", COMMENT_ROW,
            publicationId, MAX_COMMENTS);
        if (!before.equals(publications.commentScope(principal, projectId, publicationId, false))) {
            throw missing();
        }
        String author = principal.user().id();
        String workspace = principal.user().workspaceId();
        return new Items<>(rows.stream().filter(row -> visible(before, workspace, row))
            .map(row -> row.view(author, before)).toList());
    }

    /**
     * Appends original plain text or returns the exact already-committed request.
     *
     * @param principal owner or editor's current session
     * @param projectId collaboration project
     * @param publicationId exact published version reference
     * @param key canonical client retry UUID
     * @param content explicitly entered original plain text
     * @return created or idempotently retrieved comment
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Comment create(Principal principal, String projectId, String publicationId, String key,
        String content) {
        requireReady();
        AssistantProjectCollaboration.requireId(key);
        requireContent(content);
        PublicationScope access = publications.commentScope(principal, projectId, publicationId,
            true);
        String author = principal.user().id();
        String workspace = principal.user().workspaceId();
        String id = commentId(workspace, publicationId, author, key);
        if (journal.blocksArtifactComment(access.ownerId(), workspace, id)) {
            throw missing();
        }
        Row previous = stored(publicationId, id);
        if (previous != null) {
            if (!author.equals(previous.author()) || !content.equals(previous.content())) {
                throw conflict("This request key already has different comment content.");
            }
            return previous.view(author, access);
        }
        Integer count = jdbc.queryForObject("""
            SELECT COUNT(*) FROM assistant_project_artifact_comment WHERE publication_id = ?
            """, Integer.class, publicationId);
        if (count != null && count >= MAX_COMMENTS) {
            throw conflict("The published version already has 64 comments.");
        }
        Instant now = Instant.ofEpochMilli(System.currentTimeMillis());
        jdbc.update("""
            INSERT INTO assistant_project_artifact_comment
                (id, publication_id, author_id, content, created_at) VALUES (?, ?, ?, ?, ?)
            """, id, publicationId, author, content, now.toEpochMilli());
        return new Comment(id, content, now, true, true);
    }

    /**
     * Deletes a comment for the owner or its current editor author.
     *
     * @param principal owner or editor's current session
     * @param projectId collaboration project
     * @param publicationId exact published version reference
     * @param id scoped comment identifier
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void delete(Principal principal, String projectId, String publicationId, String id) {
        requireReady();
        AssistantProjectCollaboration.requireId(id);
        PublicationScope access = publications.commentScope(principal, projectId, publicationId,
            true);
        Row row = stored(publicationId, id);
        String workspace = principal.user().workspaceId();
        if (row == null || !visible(access, workspace, row)) {
            throw missing();
        }
        if (!access.ownsProject() && !principal.user().id().equals(row.author())) {
            throw new AssistantApiException(HttpStatus.FORBIDDEN, "FORBIDDEN",
                "The project role does not permit removing this comment.");
        }
        erasure.deleteArtifactComment(access.ownerId(), workspace, id);
    }

    private Row stored(String publicationId, String id) {
        return jdbc.query(SELECT + " AND id = ?", COMMENT_ROW, publicationId, id).stream()
            .findFirst().orElse(null);
    }

    private boolean visible(PublicationScope scope, String workspace, Row row) {
        return !journal.blocksCollaborationParticipant(row.author())
            && !journal.blocksArtifactComment(scope.ownerId(), workspace, row.id());
    }

    private void requireReady() {
        if (!enabled || !journal.enabled()) {
            throw new AssistantApiException(HttpStatus.FORBIDDEN, "CAPABILITY_UNAVAILABLE",
                "Project artifact comments are unavailable.");
        }
        if (!journal.acceptsErasure()) {
            throw new AssistantApiException(HttpStatus.SERVICE_UNAVAILABLE,
                "CAPABILITY_UNAVAILABLE",
                "Project comment revocation is temporarily unavailable.");
        }
    }

    private static void requireContent(String content) {
        if (content == null || content.length() > MAX_TEXT_BYTES || content.isBlank()
            || !StandardCharsets.UTF_8.newEncoder().canEncode(content)
            || content.getBytes(StandardCharsets.UTF_8).length > MAX_TEXT_BYTES
            || content.codePoints().anyMatch(point -> Character.isISOControl(point)
                && point != '\n' && point != '\r' && point != '\t')) {
            throw new AssistantApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                "Comments must contain valid plain text up to 4 KiB.");
        }
    }

    private static String commentId(String workspace, String publication, String author,
        String key) {
        String scope =
            "assistant-project-artifact-comment/v1:" + workspace + ":" + publication + ":"
                + author + ":" + key;
        byte[] identity;
        try {
            identity = MessageDigest.getInstance("SHA-256")
                .digest(scope.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable.", failure);
        }
        identity[6] = (byte) ((identity[6] & 0x0f) | 0x80);
        identity[8] = (byte) ((identity[8] & 0x3f) | 0x80);
        ByteBuffer bytes = ByteBuffer.wrap(identity);
        return new UUID(bytes.getLong(), bytes.getLong()).toString();
    }

    private static AssistantApiException missing() {
        return new AssistantApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Comment not found.");
    }

    private static AssistantApiException conflict(String message) {
        return new AssistantApiException(HttpStatus.CONFLICT, "CONFLICT", message);
    }

    /**
     * One comment with role-bound authorship controls.
     *
     * @param id scoped comment identifier
     * @param content original plain text
     * @param createdAt creation instant
     * @param authoredByMe whether the current actor authored this comment
     * @param canDelete whether the current role may remove it
     */
    public record Comment(String id, String content, Instant createdAt, boolean authoredByMe,
        boolean canDelete) {

        @Override
        public String toString() {
            return "Comment[protected]";
        }
    }

    private record Row(String id, String author, String content, Instant createdAt) {

        private Comment view(String reader, PublicationScope scope) {
            boolean authored = author.equals(reader);
            return new Comment(id, content, createdAt, authored,
                scope.ownsProject() || (scope.editsProject() && authored));
        }
    }
}

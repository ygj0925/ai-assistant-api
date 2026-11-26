-- liquibase formatted sql

-- changeset yuanguangji:assistant-project-artifact-comment-1
CREATE TABLE assistant_project_artifact_comment (
    id VARCHAR(36) PRIMARY KEY,
    publication_id VARCHAR(36) NOT NULL,
    author_id VARCHAR(36) NOT NULL,
    content TEXT NOT NULL,
    created_at BIGINT NOT NULL,
    CONSTRAINT fk_assistant_collab_comment_publication FOREIGN KEY (publication_id)
        REFERENCES assistant_project_artifact_publication (id) ON DELETE CASCADE,
    CONSTRAINT fk_assistant_collab_comment_author FOREIGN KEY (author_id)
        REFERENCES assistant_account (id),
    CONSTRAINT chk_assistant_collab_comment CHECK (OCTET_LENGTH(content) BETWEEN 1 AND 4096)
);

CREATE INDEX idx_assistant_collab_comment_publication
    ON assistant_project_artifact_comment (publication_id, created_at, id);
CREATE INDEX idx_assistant_collab_comment_author
    ON assistant_project_artifact_comment (author_id);

-- changeset yuanguangji:assistant-project-artifact-comment-2
ALTER TABLE assistant_deletion_applied DROP CONSTRAINT chk_assistant_deletion_kind,
    ADD CONSTRAINT chk_assistant_deletion_kind CHECK (kind IN (
        'ACCOUNT', 'CONVERSATION', 'SHARE', 'RESOURCE', 'MEMORY', 'MEMORY_OFF',
        'PROJECT_MEM_OFF', 'HISTORY_OFF', 'INSTRUCTIONS_OFF', 'SHARE_LOCK', 'SHARE_UNLOCK',
        'ATTACHMENT', 'RESEARCH_REVOKE', 'REMINDER_DELETE', 'REMINDER_PAUSE',
        'CUSTOM_DELETE', 'CUSTOM_REVISE', 'REMINDER_SHARE', 'COLLAB_GRANT', 'COLLAB_TEXT',
        'ARTIFACT', 'COLLAB_ARTIFACT', 'COLLAB_COMMENT'
    ));

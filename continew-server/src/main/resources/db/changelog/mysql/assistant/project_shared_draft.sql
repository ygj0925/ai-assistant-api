-- liquibase formatted sql

-- changeset yuanguangji:assistant-project-shared-draft-1
CREATE TABLE assistant_project_shared_draft (
    id VARCHAR(36) PRIMARY KEY,
    workspace_id VARCHAR(36) NOT NULL,
    owner_id VARCHAR(36) NOT NULL,
    project_id VARCHAR(36) NOT NULL,
    updated_at BIGINT NOT NULL,
    CONSTRAINT fk_assistant_shared_draft_project FOREIGN KEY (workspace_id, owner_id, project_id)
        REFERENCES assistant_project (workspace_id, account_id, id) ON DELETE CASCADE
);

CREATE INDEX idx_assistant_shared_draft_scope
    ON assistant_project_shared_draft (workspace_id, owner_id, project_id, updated_at, id);

-- changeset yuanguangji:assistant-project-shared-draft-2
CREATE TABLE assistant_project_shared_draft_revision (
    id VARCHAR(36) PRIMARY KEY,
    draft_id VARCHAR(36) NOT NULL,
    version_no INTEGER NOT NULL,
    author_id VARCHAR(36) NOT NULL,
    title VARCHAR(120) NOT NULL,
    content TEXT NOT NULL,
    restored_from_revision_id VARCHAR(36),
    created_at BIGINT NOT NULL,
    CONSTRAINT fk_assistant_shared_revision_draft FOREIGN KEY (draft_id)
        REFERENCES assistant_project_shared_draft (id) ON DELETE CASCADE,
    CONSTRAINT fk_assistant_shared_revision_author FOREIGN KEY (author_id)
        REFERENCES assistant_account (id),
    CONSTRAINT uq_assistant_shared_revision_no UNIQUE (draft_id, version_no),
    CONSTRAINT chk_assistant_shared_revision CHECK (
        version_no BETWEEN 1 AND 32 AND CHAR_LENGTH(title) BETWEEN 1 AND 120
        AND OCTET_LENGTH(content) BETWEEN 0 AND 32768
    )
);

CREATE INDEX idx_assistant_shared_revision_author
    ON assistant_project_shared_draft_revision (author_id, draft_id);

-- changeset yuanguangji:assistant-project-shared-draft-3
ALTER TABLE assistant_deletion_applied DROP CHECK chk_assistant_deletion_kind,
    ADD CONSTRAINT chk_assistant_deletion_kind CHECK (kind IN (
        'ACCOUNT', 'CONVERSATION', 'SHARE', 'RESOURCE', 'MEMORY', 'MEMORY_OFF',
        'PROJECT_MEM_OFF', 'HISTORY_OFF', 'INSTRUCTIONS_OFF', 'SHARE_LOCK', 'SHARE_UNLOCK',
        'ATTACHMENT', 'RESEARCH_REVOKE', 'REMINDER_DELETE', 'REMINDER_PAUSE',
        'CUSTOM_DELETE', 'CUSTOM_REVISE', 'REMINDER_SHARE', 'COLLAB_GRANT', 'COLLAB_TEXT',
        'ARTIFACT', 'COLLAB_ARTIFACT', 'COLLAB_COMMENT', 'COLLAB_DRAFT'
    ));

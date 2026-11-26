-- liquibase formatted sql

-- changeset yuanguangji:assistant-project-collaboration-1
CREATE TABLE assistant_project_collab_grant (
    id VARCHAR(36) PRIMARY KEY,
    workspace_id VARCHAR(36) NOT NULL,
    owner_id VARCHAR(36) NOT NULL,
    project_id VARCHAR(36) NOT NULL,
    member_id VARCHAR(36) NOT NULL,
    role VARCHAR(8) NOT NULL,
    state VARCHAR(8) NOT NULL,
    created_at BIGINT NOT NULL,
    expires_at BIGINT NOT NULL,
    accepted_at BIGINT,
    revoked_at BIGINT,
    CONSTRAINT fk_assistant_collab_grant_project FOREIGN KEY (workspace_id, owner_id, project_id)
        REFERENCES assistant_project (workspace_id, account_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_assistant_collab_grant_member FOREIGN KEY (member_id)
        REFERENCES assistant_account (id),
    CONSTRAINT chk_assistant_collab_grant CHECK (
        owner_id <> member_id AND expires_at > created_at AND role IN ('viewer', 'editor') AND (
            (state = 'pending' AND accepted_at IS NULL AND revoked_at IS NULL)
            OR (state = 'accepted' AND accepted_at IS NOT NULL AND revoked_at IS NULL)
            OR (state = 'revoked' AND revoked_at IS NOT NULL)
        )
    )
);

CREATE INDEX idx_assistant_collab_grant_project
    ON assistant_project_collab_grant (workspace_id, owner_id, project_id, state);
CREATE INDEX idx_assistant_collab_grant_member
    ON assistant_project_collab_grant (member_id, workspace_id, state);
CREATE INDEX idx_assistant_collab_grant_owner_time
    ON assistant_project_collab_grant (owner_id, created_at);

-- changeset yuanguangji:assistant-project-collaboration-2
CREATE TABLE assistant_project_collab_text (
    id VARCHAR(36) PRIMARY KEY,
    workspace_id VARCHAR(36) NOT NULL,
    owner_id VARCHAR(36) NOT NULL,
    project_id VARCHAR(36) NOT NULL,
    author_id VARCHAR(36) NOT NULL,
    name VARCHAR(120) NOT NULL,
    content TEXT NOT NULL,
    created_at BIGINT NOT NULL,
    CONSTRAINT fk_assistant_collab_text_project FOREIGN KEY (workspace_id, owner_id, project_id)
        REFERENCES assistant_project (workspace_id, account_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_assistant_collab_text_author FOREIGN KEY (author_id)
        REFERENCES assistant_account (id)
);

CREATE INDEX idx_assistant_collab_text_project
    ON assistant_project_collab_text (workspace_id, owner_id, project_id, created_at, id);
CREATE INDEX idx_assistant_collab_text_author ON assistant_project_collab_text (author_id);

-- changeset yuanguangji:assistant-project-collaboration-3
ALTER TABLE assistant_deletion_applied DROP CHECK chk_assistant_deletion_kind,
    ADD CONSTRAINT chk_assistant_deletion_kind CHECK (kind IN (
        'ACCOUNT', 'CONVERSATION', 'SHARE', 'RESOURCE', 'MEMORY', 'MEMORY_OFF',
        'PROJECT_MEM_OFF', 'HISTORY_OFF', 'INSTRUCTIONS_OFF', 'SHARE_LOCK', 'SHARE_UNLOCK',
        'ATTACHMENT', 'RESEARCH_REVOKE', 'REMINDER_DELETE', 'REMINDER_PAUSE',
        'CUSTOM_DELETE', 'CUSTOM_REVISE', 'REMINDER_SHARE', 'COLLAB_GRANT', 'COLLAB_TEXT'
    ));

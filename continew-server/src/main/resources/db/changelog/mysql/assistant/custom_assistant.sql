-- liquibase formatted sql

-- changeset yuanguangji:assistant-custom-1
CREATE TABLE assistant_custom_assistant (
    id VARCHAR(36) PRIMARY KEY,
    account_id VARCHAR(36) NOT NULL,
    workspace_id VARCHAR(36) NOT NULL,
    project_id VARCHAR(36),
    head_version_id VARCHAR(36) NOT NULL,
    head_epoch BIGINT NOT NULL,
    request_digest CHAR(64) NOT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    CONSTRAINT fk_assistant_custom_account FOREIGN KEY (account_id) REFERENCES assistant_account (id),
    CONSTRAINT fk_assistant_custom_workspace FOREIGN KEY (workspace_id) REFERENCES assistant_workspace (id),
    CONSTRAINT fk_assistant_custom_project FOREIGN KEY (workspace_id, account_id, project_id)
        REFERENCES assistant_project (workspace_id, account_id, id),
    CONSTRAINT uq_assistant_custom_owner UNIQUE (workspace_id, account_id, id, project_id),
    CONSTRAINT chk_assistant_custom_epoch CHECK (head_epoch > 0)
);
CREATE INDEX idx_assistant_custom_owner ON assistant_custom_assistant (account_id, workspace_id, created_at, id);

-- changeset yuanguangji:assistant-custom-2
CREATE TABLE assistant_custom_assistant_version (
    id VARCHAR(36) PRIMARY KEY,
    assistant_id VARCHAR(36) NOT NULL,
    version_no INTEGER NOT NULL,
    name VARCHAR(120) NOT NULL,
    instructions VARCHAR(2000) NOT NULL,
    tool_id VARCHAR(32),
    restored_from_version_id VARCHAR(36),
    created_at BIGINT NOT NULL,
    CONSTRAINT fk_assistant_custom_version FOREIGN KEY (assistant_id)
        REFERENCES assistant_custom_assistant (id) ON DELETE CASCADE,
    CONSTRAINT uq_assistant_custom_version_number UNIQUE (assistant_id, version_no),
    CONSTRAINT uq_assistant_custom_version_identity UNIQUE (assistant_id, id),
    CONSTRAINT chk_assistant_custom_definition CHECK (
        version_no BETWEEN 1 AND 20 AND CHAR_LENGTH(name) > 0
        AND (tool_id IS NULL OR tool_id = 'project_resource_lookup')
    )
);

-- changeset yuanguangji:assistant-custom-3
CREATE TABLE assistant_custom_assistant_source (
    assistant_id VARCHAR(36) NOT NULL,
    version_id VARCHAR(36) NOT NULL,
    account_id VARCHAR(36) NOT NULL,
    workspace_id VARCHAR(36) NOT NULL,
    project_id VARCHAR(36) NOT NULL,
    resource_id VARCHAR(36) NOT NULL,
    position_no INTEGER NOT NULL,
    PRIMARY KEY (version_id, position_no),
    CONSTRAINT uq_assistant_custom_source UNIQUE (version_id, resource_id),
    CONSTRAINT fk_assistant_custom_source_scope FOREIGN KEY (workspace_id, account_id, assistant_id, project_id)
        REFERENCES assistant_custom_assistant (workspace_id, account_id, id, project_id) ON DELETE CASCADE,
    CONSTRAINT fk_assistant_custom_source_version FOREIGN KEY (assistant_id, version_id)
        REFERENCES assistant_custom_assistant_version (assistant_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_assistant_custom_source_material FOREIGN KEY (workspace_id, account_id, project_id, resource_id)
        REFERENCES assistant_project_resource (workspace_id, account_id, project_id, id),
    CONSTRAINT chk_assistant_custom_source_position CHECK (position_no BETWEEN 1 AND 8)
);

-- changeset yuanguangji:assistant-custom-4
ALTER TABLE assistant_deletion_applied DROP CHECK chk_assistant_deletion_kind,
    ADD CONSTRAINT chk_assistant_deletion_kind CHECK (kind IN (
        'ACCOUNT', 'CONVERSATION', 'SHARE', 'RESOURCE', 'MEMORY', 'MEMORY_OFF', 'PROJECT_MEM_OFF',
        'HISTORY_OFF', 'INSTRUCTIONS_OFF', 'SHARE_LOCK', 'SHARE_UNLOCK', 'ATTACHMENT', 'RESEARCH_REVOKE',
        'REMINDER_DELETE', 'REMINDER_PAUSE', 'CUSTOM_DELETE', 'CUSTOM_REVISE'
    ));

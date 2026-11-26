-- liquibase formatted sql

-- changeset yuanguangji:assistant-memory-1
CREATE TABLE assistant_memory_settings (
    account_id VARCHAR(36) NOT NULL,
    workspace_id VARCHAR(36) NOT NULL,
    scope_kind VARCHAR(8) NOT NULL,
    scope_id VARCHAR(36) NOT NULL,
    project_id VARCHAR(36),
    memory_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    reference_history BOOLEAN NOT NULL DEFAULT FALSE,
    instructions VARCHAR(2000) NOT NULL DEFAULT '',
    memory_epoch BIGINT NOT NULL DEFAULT 0,
    history_epoch BIGINT NOT NULL DEFAULT 0,
    instructions_epoch BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (account_id, workspace_id, scope_kind, scope_id),
    FOREIGN KEY (account_id) REFERENCES assistant_account (id),
    FOREIGN KEY (workspace_id) REFERENCES assistant_workspace (id),
    FOREIGN KEY (workspace_id, account_id, project_id) REFERENCES assistant_project (workspace_id, account_id, id),
    CONSTRAINT chk_assistant_memory_scope CHECK (
        (scope_kind = 'personal' AND project_id IS NULL AND scope_id = workspace_id)
        OR (scope_kind = 'project' AND project_id IS NOT NULL AND scope_id = project_id
            AND reference_history = FALSE AND CHAR_LENGTH(instructions) = 0)
    ),
    CONSTRAINT chk_assistant_memory_epochs CHECK (memory_epoch >= 0 AND history_epoch >= 0 AND instructions_epoch >= 0)
);

-- changeset yuanguangji:assistant-memory-2
CREATE TABLE assistant_memory_entry (
    id VARCHAR(36) PRIMARY KEY,
    account_id VARCHAR(36) NOT NULL,
    workspace_id VARCHAR(36) NOT NULL,
    scope_kind VARCHAR(8) NOT NULL,
    scope_id VARCHAR(36) NOT NULL,
    content VARCHAR(2000) NOT NULL,
    created_at BIGINT NOT NULL,
    deleted_at BIGINT,
    FOREIGN KEY (account_id, workspace_id, scope_kind, scope_id)
        REFERENCES assistant_memory_settings (account_id, workspace_id, scope_kind, scope_id) ON DELETE CASCADE,
    CONSTRAINT chk_assistant_memory_content CHECK (
        (deleted_at IS NULL AND CHAR_LENGTH(content) > 0) OR (deleted_at IS NOT NULL AND CHAR_LENGTH(content) = 0)
    )
);

-- changeset yuanguangji:assistant-memory-3
CREATE INDEX idx_assistant_memory_scope_order
    ON assistant_memory_entry (account_id, workspace_id, scope_kind, scope_id, created_at, id);

-- changeset yuanguangji:assistant-memory-4
ALTER TABLE assistant_deletion_applied DROP CHECK chk_assistant_deletion_scope,
    ADD CONSTRAINT chk_assistant_deletion_kind CHECK (kind IN (
        'ACCOUNT', 'CONVERSATION', 'SHARE', 'RESOURCE', 'MEMORY', 'MEMORY_OFF', 'PROJECT_MEM_OFF',
        'HISTORY_OFF', 'INSTRUCTIONS_OFF'
    ));

-- liquibase formatted sql

-- changeset yuanguangji:assistant-project-resource-1
CREATE TABLE assistant_project_resource (
    id VARCHAR(36) PRIMARY KEY,
    workspace_id VARCHAR(36) NOT NULL,
    account_id VARCHAR(36) NOT NULL,
    project_id VARCHAR(36) NOT NULL,
    name VARCHAR(120) NOT NULL,
    content TEXT NOT NULL,
    search_name TEXT COLLATE "C" NOT NULL,
    search_content TEXT COLLATE "C" NOT NULL,
    created_at BIGINT NOT NULL,
    deleted_at BIGINT,
    CONSTRAINT fk_assistant_project_resource_owner
        FOREIGN KEY (workspace_id, account_id, project_id)
        REFERENCES assistant_project (workspace_id, account_id, id),
    CONSTRAINT chk_assistant_project_resource_body CHECK (
        (deleted_at IS NULL AND CHAR_LENGTH(name) > 0 AND OCTET_LENGTH(content) BETWEEN 1 AND 16384)
        OR (deleted_at IS NOT NULL AND name = '' AND content = '' AND search_name = '' AND search_content = '')
    )
);

-- changeset yuanguangji:assistant-project-resource-2
CREATE INDEX idx_assistant_project_resource_list
    ON assistant_project_resource (workspace_id, account_id, project_id, deleted_at, created_at, id);

-- changeset yuanguangji:assistant-project-resource-3
CREATE INDEX idx_assistant_project_resource_account
    ON assistant_project_resource (account_id);

-- changeset yuanguangji:assistant-project-resource-4
ALTER TABLE assistant_deletion_applied DROP CONSTRAINT chk_assistant_deletion_kind,
    ADD CONSTRAINT chk_assistant_deletion_scope CHECK (kind IN ('ACCOUNT', 'CONVERSATION', 'SHARE', 'RESOURCE'));

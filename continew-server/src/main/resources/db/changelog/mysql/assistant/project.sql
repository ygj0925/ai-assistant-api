-- liquibase formatted sql

-- changeset yuanguangji:assistant-project-1
CREATE TABLE assistant_project (
    id VARCHAR(36) PRIMARY KEY,
    workspace_id VARCHAR(36) NOT NULL,
    account_id VARCHAR(36) NOT NULL,
    name VARCHAR(120) NOT NULL,
    description VARCHAR(4000) NOT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    deleted_at BIGINT,
    UNIQUE (workspace_id, account_id, id),
    FOREIGN KEY (workspace_id) REFERENCES assistant_workspace (id),
    FOREIGN KEY (account_id) REFERENCES assistant_account (id)
);

-- changeset yuanguangji:assistant-project-2
CREATE INDEX idx_assistant_project_catalog
    ON assistant_project (workspace_id, account_id, deleted_at, created_at, id);

-- changeset yuanguangji:assistant-project-3
ALTER TABLE assistant_conversation ADD COLUMN project_id VARCHAR(36);

-- changeset yuanguangji:assistant-project-4
ALTER TABLE assistant_conversation ADD CONSTRAINT fk_assistant_conversation_project
    FOREIGN KEY (workspace_id, account_id, project_id) REFERENCES assistant_project (workspace_id, account_id, id);

-- changeset yuanguangji:assistant-project-5
CREATE INDEX idx_assistant_conversation_project
    ON assistant_conversation (workspace_id, account_id, project_id, deleted_at, pinned, updated_at, id);


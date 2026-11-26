-- liquibase formatted sql

-- changeset yuanguangji:assistant-conversation-1
CREATE TABLE assistant_conversation (
    id VARCHAR(36) PRIMARY KEY,
    workspace_id VARCHAR(36) NOT NULL,
    account_id VARCHAR(36) NOT NULL,
    title VARCHAR(256) NOT NULL,
    title_search VARCHAR(768) NOT NULL,
    pinned BOOLEAN NOT NULL,
    archived BOOLEAN NOT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    deleted_at BIGINT,
    FOREIGN KEY (workspace_id) REFERENCES assistant_workspace (id),
    FOREIGN KEY (account_id) REFERENCES assistant_account (id)
);

-- changeset yuanguangji:assistant-conversation-2
CREATE INDEX idx_assistant_conversation_catalog
    ON assistant_conversation (workspace_id, account_id, deleted_at, pinned, updated_at, id);

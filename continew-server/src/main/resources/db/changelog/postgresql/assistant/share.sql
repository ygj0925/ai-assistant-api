-- liquibase formatted sql

-- changeset yuanguangji:assistant-share-1
CREATE TABLE assistant_share (
    id VARCHAR(36) PRIMARY KEY,
    workspace_id VARCHAR(36) NOT NULL,
    account_id VARCHAR(36) NOT NULL,
    conversation_id VARCHAR(36) NOT NULL,
    source_message_id VARCHAR(36) NOT NULL,
    scope VARCHAR(16) NOT NULL CHECK (scope IN ('branch', 'reply')),
    audience VARCHAR(16) NOT NULL CHECK (audience IN ('link', 'workspace')),
    frozen_title VARCHAR(256) NOT NULL,
    snapshot_digest CHAR(64) COLLATE "C",
    token_digest CHAR(64) COLLATE "C" NOT NULL UNIQUE,
    created_at BIGINT NOT NULL,
    expires_at BIGINT NOT NULL CHECK (expires_at > created_at),
    revoked_at BIGINT,
    CONSTRAINT chk_assistant_share_snapshot CHECK (revoked_at IS NOT NULL OR snapshot_digest IS NOT NULL),
    FOREIGN KEY (workspace_id, account_id, conversation_id)
        REFERENCES assistant_conversation (workspace_id, account_id, id),
    FOREIGN KEY (conversation_id, source_message_id)
        REFERENCES assistant_chat_message (conversation_id, id)
);

-- changeset yuanguangji:assistant-share-2
CREATE INDEX idx_assistant_share_catalog
    ON assistant_share (workspace_id, account_id, conversation_id, created_at, id);

-- changeset yuanguangji:assistant-share-3
CREATE TABLE assistant_share_copy (
    share_id VARCHAR(36) NOT NULL,
    workspace_id VARCHAR(36) NOT NULL,
    account_id VARCHAR(36) NOT NULL,
    conversation_id VARCHAR(36) NOT NULL UNIQUE,
    PRIMARY KEY (share_id, workspace_id, account_id),
    FOREIGN KEY (share_id) REFERENCES assistant_share (id),
    FOREIGN KEY (workspace_id, account_id, conversation_id)
        REFERENCES assistant_conversation (workspace_id, account_id, id)
);

-- changeset yuanguangji:assistant-share-4
CREATE INDEX idx_assistant_share_quota ON assistant_share (account_id, expires_at, revoked_at);
CREATE INDEX idx_assistant_share_copy_recipient ON assistant_share_copy (account_id);

-- changeset yuanguangji:assistant-share-5
ALTER TABLE assistant_deletion_applied DROP CONSTRAINT assistant_deletion_applied_kind_check,
    ADD CONSTRAINT chk_assistant_deletion_kind CHECK (kind IN ('ACCOUNT', 'CONVERSATION', 'SHARE'));

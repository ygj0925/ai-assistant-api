-- liquibase formatted sql

-- changeset yuanguangji:assistant-reminder-share-1
CREATE TABLE assistant_reminder_share (
    id VARCHAR(36) PRIMARY KEY,
    account_id VARCHAR(36) NOT NULL,
    workspace_id VARCHAR(36) NOT NULL,
    reminder_id VARCHAR(36) NOT NULL,
    source_epoch BIGINT NOT NULL,
    token_digest CHAR(64) NOT NULL UNIQUE,
    revision_digest CHAR(64) NOT NULL,
    created_at BIGINT NOT NULL,
    expires_at BIGINT NOT NULL,
    revoked_at BIGINT,
    CONSTRAINT fk_assistant_reminder_share_source
        FOREIGN KEY (reminder_id) REFERENCES assistant_reminder (id) ON DELETE CASCADE,
    CONSTRAINT chk_assistant_reminder_share CHECK (source_epoch > 0 AND expires_at > created_at)
);

-- changeset yuanguangji:assistant-reminder-share-2
CREATE INDEX idx_assistant_reminder_share_owner ON assistant_reminder_share (account_id, created_at);
CREATE INDEX idx_assistant_reminder_share_source
    ON assistant_reminder_share (workspace_id, account_id, reminder_id, created_at, id);

-- changeset yuanguangji:assistant-reminder-share-3
ALTER TABLE assistant_deletion_applied DROP CONSTRAINT chk_assistant_deletion_kind,
    ADD CONSTRAINT chk_assistant_deletion_kind CHECK (kind IN (
        'ACCOUNT', 'CONVERSATION', 'SHARE', 'RESOURCE', 'MEMORY', 'MEMORY_OFF',
        'PROJECT_MEM_OFF', 'HISTORY_OFF', 'INSTRUCTIONS_OFF', 'SHARE_LOCK', 'SHARE_UNLOCK',
        'ATTACHMENT', 'RESEARCH_REVOKE', 'REMINDER_DELETE', 'REMINDER_PAUSE',
        'CUSTOM_DELETE', 'CUSTOM_REVISE', 'REMINDER_SHARE'
    ));

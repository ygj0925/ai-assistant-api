-- liquibase formatted sql

-- changeset yuanguangji:assistant-conversation-read-lock-1
ALTER TABLE assistant_conversation
    ADD COLUMN read_locked BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN read_lock_revision CHAR(64),
    ADD CONSTRAINT chk_assistant_conversation_read_lock
        CHECK (read_locked = FALSE OR read_lock_revision IS NOT NULL);

CREATE INDEX idx_assistant_conversation_read_locked
    ON assistant_conversation (workspace_id, account_id, read_locked, deleted_at, id);

-- changeset yuanguangji:assistant-conversation-read-lock-2
ALTER TABLE assistant_deletion_applied DROP CHECK chk_assistant_deletion_kind,
    ADD CONSTRAINT chk_assistant_deletion_kind CHECK (kind IN (
        'ACCOUNT', 'CONVERSATION', 'SHARE', 'RESOURCE', 'MEMORY', 'MEMORY_OFF',
        'PROJECT_MEM_OFF', 'HISTORY_OFF', 'INSTRUCTIONS_OFF', 'SHARE_LOCK', 'SHARE_UNLOCK',
        'ATTACHMENT', 'RESEARCH_REVOKE', 'REMINDER_DELETE', 'REMINDER_PAUSE',
        'CUSTOM_DELETE', 'CUSTOM_REVISE', 'REMINDER_SHARE', 'COLLAB_GRANT', 'COLLAB_TEXT',
        'ARTIFACT', 'COLLAB_ARTIFACT', 'COLLAB_COMMENT', 'COLLAB_DRAFT', 'WORKSPACE_LEAVE',
        'CONV_RD_LOCK', 'CONV_RD_OPEN'
    ));

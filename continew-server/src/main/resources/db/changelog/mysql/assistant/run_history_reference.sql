-- liquibase formatted sql

-- changeset yuanguangji:assistant-run-history-reference-1
CREATE UNIQUE INDEX uq_assistant_run_full_scope
    ON assistant_run (id, workspace_id, account_id, conversation_id);

-- changeset yuanguangji:assistant-run-history-reference-2
CREATE TABLE assistant_run_history_reference (
    run_id VARCHAR(36) PRIMARY KEY,
    account_id VARCHAR(36) NOT NULL,
    workspace_id VARCHAR(36) NOT NULL,
    target_conversation_id VARCHAR(36) NOT NULL,
    source_conversation_id VARCHAR(36),
    source_message_id VARCHAR(36),
    history_epoch BIGINT NOT NULL,
    source_read_revision CHAR(64),
    target_read_revision CHAR(64),
    source_placement_revision CHAR(64),
    target_placement_revision CHAR(64),
    used_at BIGINT,
    CONSTRAINT chk_assistant_history_scope CHECK (
        history_epoch > 0 AND (
            (source_conversation_id IS NULL AND source_message_id IS NULL)
            OR (source_conversation_id IS NOT NULL AND source_message_id IS NOT NULL
                AND source_conversation_id <> target_conversation_id)
        )
    ),
    FOREIGN KEY (run_id, workspace_id, account_id, target_conversation_id)
        REFERENCES assistant_run (id, workspace_id, account_id, conversation_id) ON DELETE CASCADE,
    FOREIGN KEY (workspace_id, account_id, source_conversation_id)
        REFERENCES assistant_conversation (workspace_id, account_id, id),
    FOREIGN KEY (source_conversation_id, source_message_id)
        REFERENCES assistant_chat_message (conversation_id, id)
);

CREATE INDEX idx_assistant_history_source
    ON assistant_run_history_reference (account_id, workspace_id, source_conversation_id);
CREATE INDEX idx_assistant_history_target
    ON assistant_run_history_reference (account_id, workspace_id, target_conversation_id);

-- changeset yuanguangji:assistant-run-history-reference-3
ALTER TABLE assistant_deletion_applied DROP CHECK chk_assistant_deletion_kind,
    ADD CONSTRAINT chk_assistant_deletion_kind CHECK (kind IN (
        'ACCOUNT', 'CONVERSATION', 'SHARE', 'RESOURCE', 'MEMORY', 'MEMORY_OFF',
        'PROJECT_MEM_OFF', 'HISTORY_OFF', 'INSTRUCTIONS_OFF', 'SHARE_LOCK', 'SHARE_UNLOCK',
        'ATTACHMENT', 'RESEARCH_REVOKE', 'REMINDER_DELETE', 'REMINDER_PAUSE',
        'CUSTOM_DELETE', 'CUSTOM_REVISE', 'REMINDER_SHARE', 'COLLAB_GRANT', 'COLLAB_TEXT',
        'ARTIFACT', 'COLLAB_ARTIFACT', 'COLLAB_COMMENT', 'COLLAB_DRAFT', 'WORKSPACE_LEAVE',
        'CONV_RD_LOCK', 'CONV_RD_OPEN', 'CONV_HIST_FENCE'
    ));

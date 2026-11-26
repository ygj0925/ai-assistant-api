-- liquibase formatted sql

-- changeset yuanguangji:assistant-artifact-1
CREATE TABLE assistant_artifact (
    id VARCHAR(36) PRIMARY KEY,
    account_id VARCHAR(36) NOT NULL,
    workspace_id VARCHAR(36) NOT NULL,
    project_id VARCHAR(36),
    source_conversation_id VARCHAR(36),
    source_message_id VARCHAR(36),
    format VARCHAR(8) NOT NULL,
    head_version_id VARCHAR(36) NOT NULL,
    request_digest CHAR(64) NOT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    CONSTRAINT fk_assistant_artifact_account FOREIGN KEY (account_id) REFERENCES assistant_account (id),
    CONSTRAINT fk_assistant_artifact_workspace FOREIGN KEY (workspace_id) REFERENCES assistant_workspace (id),
    CONSTRAINT fk_assistant_artifact_project FOREIGN KEY (workspace_id, account_id, project_id)
        REFERENCES assistant_project (workspace_id, account_id, id),
    CONSTRAINT fk_assistant_artifact_conversation FOREIGN KEY (workspace_id, account_id, source_conversation_id)
        REFERENCES assistant_conversation (workspace_id, account_id, id),
    CONSTRAINT fk_assistant_artifact_message FOREIGN KEY (source_conversation_id, source_message_id)
        REFERENCES assistant_chat_message (conversation_id, id),
    CONSTRAINT chk_assistant_artifact_scope CHECK (
        format IN ('text', 'markdown') AND (
            (source_conversation_id IS NULL AND source_message_id IS NULL AND project_id IS NOT NULL)
            OR (source_conversation_id IS NOT NULL AND source_message_id IS NOT NULL)
        )
    )
);
CREATE INDEX idx_assistant_artifact_owner ON assistant_artifact (workspace_id, account_id, id);
CREATE INDEX idx_assistant_artifact_project ON assistant_artifact (workspace_id, account_id, project_id, id);
CREATE INDEX idx_assistant_artifact_conversation
    ON assistant_artifact (workspace_id, account_id, source_conversation_id);

-- changeset yuanguangji:assistant-artifact-2
CREATE TABLE assistant_artifact_version (
    id VARCHAR(36) PRIMARY KEY,
    artifact_id VARCHAR(36) NOT NULL,
    version_no INTEGER NOT NULL,
    title VARCHAR(120) NOT NULL,
    size_bytes INTEGER NOT NULL,
    content TEXT NOT NULL,
    restored_from_version_id VARCHAR(36),
    created_at BIGINT NOT NULL,
    CONSTRAINT fk_assistant_artifact_version FOREIGN KEY (artifact_id)
        REFERENCES assistant_artifact (id) ON DELETE CASCADE,
    CONSTRAINT uq_assistant_artifact_version_number UNIQUE (artifact_id, version_no),
    CONSTRAINT chk_assistant_artifact_version CHECK (
        CHAR_LENGTH(title) > 0 AND version_no BETWEEN 1 AND 32 AND size_bytes BETWEEN 0 AND 32768
        AND size_bytes = OCTET_LENGTH(content)
    )
);

-- changeset yuanguangji:assistant-artifact-3
ALTER TABLE assistant_deletion_applied DROP CHECK chk_assistant_deletion_kind,
    ADD CONSTRAINT chk_assistant_deletion_kind CHECK (kind IN (
        'ACCOUNT', 'CONVERSATION', 'SHARE', 'RESOURCE', 'MEMORY', 'MEMORY_OFF',
        'PROJECT_MEM_OFF', 'HISTORY_OFF', 'INSTRUCTIONS_OFF', 'SHARE_LOCK', 'SHARE_UNLOCK',
        'ATTACHMENT', 'RESEARCH_REVOKE', 'REMINDER_DELETE', 'REMINDER_PAUSE',
        'CUSTOM_DELETE', 'CUSTOM_REVISE', 'REMINDER_SHARE', 'COLLAB_GRANT', 'COLLAB_TEXT', 'ARTIFACT'
    ));


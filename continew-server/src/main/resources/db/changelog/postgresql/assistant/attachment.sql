-- liquibase formatted sql

-- changeset yuanguangji:assistant-attachment-1
CREATE TABLE assistant_attachment (
    id VARCHAR(36) PRIMARY KEY,
    workspace_id VARCHAR(36) NOT NULL,
    account_id VARCHAR(36) NOT NULL,
    project_id VARCHAR(36) NOT NULL,
    idempotency_key VARCHAR(36),
    original_digest CHAR(64),
    approved_digest CHAR(64),
    status VARCHAR(16) NOT NULL,
    size_bytes INTEGER NOT NULL,
    width INTEGER NOT NULL,
    height INTEGER NOT NULL,
    error_code VARCHAR(32),
    scan_attempts INTEGER NOT NULL DEFAULT 0,
    raw_cleaned BOOLEAN NOT NULL DEFAULT FALSE,
    due_at BIGINT NOT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    CONSTRAINT fk_assistant_attachment_project
        FOREIGN KEY (workspace_id, account_id, project_id)
        REFERENCES assistant_project (workspace_id, account_id, id),
    CONSTRAINT uq_assistant_attachment_upload
        UNIQUE (workspace_id, account_id, project_id, idempotency_key),
    CONSTRAINT chk_assistant_attachment_status CHECK (
        status IN ('receiving', 'quarantined', 'approved', 'rejected', 'revoked')
        AND size_bytes BETWEEN 0 AND 1048576 AND width BETWEEN 0 AND 2048 AND height BETWEEN 0 AND 2048
        AND (status <> 'approved' OR approved_digest IS NOT NULL)
    )
);

-- changeset yuanguangji:assistant-attachment-2
CREATE INDEX idx_assistant_attachment_work ON assistant_attachment (status, due_at, id);
CREATE INDEX idx_assistant_attachment_owner ON assistant_attachment (account_id, workspace_id, project_id);

-- changeset yuanguangji:assistant-attachment-3
CREATE TABLE assistant_attachment_grant (
    token_digest CHAR(64) PRIMARY KEY,
    attachment_id VARCHAR(36) NOT NULL,
    session_id VARCHAR(36) NOT NULL,
    expires_at BIGINT NOT NULL,
    CONSTRAINT fk_assistant_attachment_grant
        FOREIGN KEY (attachment_id) REFERENCES assistant_attachment (id) ON DELETE CASCADE
);
CREATE INDEX idx_assistant_attachment_grant_expiry ON assistant_attachment_grant (expires_at);
CREATE INDEX idx_assistant_attachment_grant_owner ON assistant_attachment_grant (attachment_id, session_id);

-- changeset yuanguangji:assistant-attachment-4
CREATE TABLE assistant_attachment_cleanup (
    event_id CHAR(64) PRIMARY KEY,
    account_id VARCHAR(36) NOT NULL,
    workspace_id VARCHAR(36),
    attachment_id VARCHAR(36),
    due_at BIGINT NOT NULL,
    CONSTRAINT chk_assistant_attachment_cleanup CHECK (
        (workspace_id IS NULL AND attachment_id IS NULL) OR
        (workspace_id IS NOT NULL AND attachment_id IS NOT NULL)
    )
);
CREATE INDEX idx_assistant_attachment_cleanup_due ON assistant_attachment_cleanup (due_at, event_id);

-- changeset yuanguangji:assistant-attachment-5
ALTER TABLE assistant_deletion_applied DROP CONSTRAINT chk_assistant_deletion_kind,
    ADD CONSTRAINT chk_assistant_deletion_kind CHECK (kind IN (
        'ACCOUNT', 'CONVERSATION', 'SHARE', 'RESOURCE', 'MEMORY', 'MEMORY_OFF', 'PROJECT_MEM_OFF',
        'HISTORY_OFF', 'INSTRUCTIONS_OFF', 'SHARE_LOCK', 'SHARE_UNLOCK', 'ATTACHMENT'
    ));

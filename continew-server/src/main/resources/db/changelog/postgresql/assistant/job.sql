-- liquibase formatted sql

-- changeset yuanguangji:assistant-job-1
CREATE TABLE assistant_job (
    id VARCHAR(36) PRIMARY KEY,
    workspace_id VARCHAR(36) NOT NULL,
    account_id VARCHAR(36) NOT NULL,
    conversation_id VARCHAR(36) NOT NULL,
    source_message_id VARCHAR(36) NOT NULL,
    idempotency_digest CHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('queued', 'running', 'completed', 'cancelled', 'failed')),
    progress INTEGER NOT NULL CHECK (progress IN (0, 50, 100)),
    artifact_id VARCHAR(36) NOT NULL UNIQUE,
    artifact_digest CHAR(64),
    error_code VARCHAR(48),
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    CONSTRAINT uq_assistant_job_idempotency UNIQUE (account_id, idempotency_digest),
    CHECK ((status = 'completed' AND progress = 100) OR (status <> 'completed' AND progress < 100)),
    CHECK ((progress = 0 AND artifact_digest IS NULL) OR (progress > 0 AND artifact_digest IS NOT NULL)),
    FOREIGN KEY (workspace_id, account_id, conversation_id)
        REFERENCES assistant_conversation (workspace_id, account_id, id),
    FOREIGN KEY (conversation_id, source_message_id) REFERENCES assistant_chat_message (conversation_id, id)
);

-- changeset yuanguangji:assistant-job-2
CREATE INDEX idx_assistant_job_list ON assistant_job (workspace_id, account_id, created_at, id);

-- changeset yuanguangji:assistant-job-3
CREATE TABLE assistant_job_outbox (
    job_id VARCHAR(36) PRIMARY KEY,
    available_at BIGINT NOT NULL,
    FOREIGN KEY (job_id) REFERENCES assistant_job (id) ON DELETE CASCADE
);

-- changeset yuanguangji:assistant-job-4
CREATE INDEX idx_assistant_job_outbox_due ON assistant_job_outbox (available_at, job_id);

-- changeset yuanguangji:assistant-job-5
CREATE INDEX idx_assistant_job_active ON assistant_job (account_id, status);

-- changeset yuanguangji:assistant-job-6
CREATE INDEX idx_assistant_job_source ON assistant_job (conversation_id);

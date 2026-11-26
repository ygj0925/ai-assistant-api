-- liquibase formatted sql

-- changeset yuanguangji:assistant-research-1
CREATE TABLE assistant_research_task (
    id VARCHAR(36) PRIMARY KEY,
    workspace_id VARCHAR(36) NOT NULL,
    account_id VARCHAR(36) NOT NULL,
    idempotency_digest CHAR(64) NOT NULL,
    revision INTEGER NOT NULL,
    state VARCHAR(20) NOT NULL,
    progress INTEGER NOT NULL,
    approval_epoch BIGINT,
    due_at BIGINT,
    error_code VARCHAR(48),
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    UNIQUE (account_id, idempotency_digest),
    FOREIGN KEY (workspace_id) REFERENCES assistant_workspace (id),
    FOREIGN KEY (account_id) REFERENCES assistant_account (id),
    CONSTRAINT chk_assistant_research_revision CHECK (revision BETWEEN 1 AND 16),
    CONSTRAINT chk_assistant_research_state CHECK (
        (state = 'awaiting_approval' AND approval_epoch IS NULL AND due_at IS NULL AND progress = 0)
        OR (state = 'queued' AND approval_epoch IS NOT NULL AND due_at IS NOT NULL AND progress = 0)
        OR (state = 'running' AND approval_epoch IS NOT NULL AND due_at IS NOT NULL AND progress = 50)
        OR (state = 'completed' AND approval_epoch IS NOT NULL AND due_at IS NULL AND progress = 100)
        OR (state IN ('cancelled', 'failed') AND approval_epoch IS NULL AND due_at IS NULL AND progress = 0)
    )
);

-- changeset yuanguangji:assistant-research-2
CREATE TABLE assistant_research_revision (
    task_id VARCHAR(36) NOT NULL,
    revision INTEGER NOT NULL,
    question VARCHAR(256) NOT NULL,
    PRIMARY KEY (task_id, revision),
    FOREIGN KEY (task_id) REFERENCES assistant_research_task (id) ON DELETE CASCADE,
    CONSTRAINT chk_assistant_research_plan CHECK (
        revision BETWEEN 1 AND 16 AND CHAR_LENGTH(question) BETWEEN 1 AND 256
    )
);

-- changeset yuanguangji:assistant-research-3
CREATE TABLE assistant_research_source (
    task_id VARCHAR(36) NOT NULL,
    revision INTEGER NOT NULL,
    ordinal SMALLINT NOT NULL,
    source_id VARCHAR(36) NOT NULL,
    fingerprint CHAR(64) NOT NULL,
    PRIMARY KEY (task_id, revision, ordinal),
    UNIQUE (task_id, revision, source_id),
    FOREIGN KEY (task_id, revision) REFERENCES assistant_research_revision (task_id, revision) ON DELETE CASCADE,
    CONSTRAINT chk_assistant_research_source CHECK (ordinal BETWEEN 0 AND 5)
);

-- changeset yuanguangji:assistant-research-4
CREATE INDEX idx_assistant_research_due ON assistant_research_task (state, due_at, id);
CREATE INDEX idx_assistant_research_scope ON assistant_research_task (workspace_id, account_id, created_at, id);

-- changeset yuanguangji:assistant-research-5
ALTER TABLE assistant_deletion_applied DROP CONSTRAINT chk_assistant_deletion_kind,
    ADD CONSTRAINT chk_assistant_deletion_kind CHECK (kind IN (
        'ACCOUNT', 'CONVERSATION', 'SHARE', 'RESOURCE', 'MEMORY', 'MEMORY_OFF', 'PROJECT_MEM_OFF',
        'HISTORY_OFF', 'INSTRUCTIONS_OFF', 'SHARE_LOCK', 'SHARE_UNLOCK', 'ATTACHMENT', 'RESEARCH_REVOKE'
    ));

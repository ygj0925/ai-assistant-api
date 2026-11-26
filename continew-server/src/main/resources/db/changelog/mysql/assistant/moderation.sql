-- liquibase formatted sql

-- changeset yuanguangji:assistant-moderation-1
CREATE TABLE assistant_content_report (
    id VARCHAR(36) PRIMARY KEY,
    workspace_id VARCHAR(36) NOT NULL,
    reporter_account_id VARCHAR(36) NOT NULL,
    target_type VARCHAR(32) NOT NULL,
    target_id VARCHAR(36) NOT NULL,
    conversation_id VARCHAR(36) NOT NULL,
    reason VARCHAR(16) NOT NULL CHECK (reason IN ('safety', 'privacy', 'spam', 'other')),
    status VARCHAR(24) NOT NULL CHECK (status IN ('open', 'dismissed', 'escalated', 'target_unavailable')),
    target_created_at BIGINT NOT NULL,
    evidence_digest CHAR(64) NOT NULL,
    created_at BIGINT NOT NULL,
    UNIQUE (workspace_id, reporter_account_id, target_type, target_id)
);

-- changeset yuanguangji:assistant-moderation-2
CREATE INDEX idx_assistant_report_workspace_queue
    ON assistant_content_report (workspace_id, status, created_at, id);

-- changeset yuanguangji:assistant-moderation-3
CREATE TABLE assistant_report_audit (
    report_id VARCHAR(36) PRIMARY KEY,
    operator_id BIGINT NOT NULL,
    operator_tenant_id BIGINT NOT NULL,
    decision VARCHAR(16) NOT NULL CHECK (decision IN ('dismiss', 'escalate')),
    resulting_status VARCHAR(24) NOT NULL
        CHECK (resulting_status IN ('dismissed', 'escalated', 'target_unavailable')),
    created_at BIGINT NOT NULL,
    FOREIGN KEY (report_id) REFERENCES assistant_content_report (id) ON DELETE CASCADE
);

-- changeset yuanguangji:assistant-moderation-4
CREATE TABLE assistant_moderation_grant (
    admin_tenant_id BIGINT NOT NULL,
    admin_user_id BIGINT NOT NULL,
    workspace_id VARCHAR(36) NOT NULL,
    can_decide BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (admin_tenant_id, admin_user_id, workspace_id),
    FOREIGN KEY (workspace_id) REFERENCES assistant_workspace (id) ON DELETE CASCADE
);


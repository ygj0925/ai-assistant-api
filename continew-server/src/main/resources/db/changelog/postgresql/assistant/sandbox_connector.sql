-- liquibase formatted sql

-- changeset yuanguangji:assistant-sandbox-connector-1
CREATE TABLE assistant_sandbox_connector_grant (
    id VARCHAR(36) PRIMARY KEY,
    account_id VARCHAR(36) NOT NULL,
    workspace_id VARCHAR(36) NOT NULL,
    membership_revision VARCHAR(36) NOT NULL,
    ledger_id VARCHAR(36) NOT NULL,
    authorized_at BIGINT NOT NULL,
    revoked_at BIGINT,
    UNIQUE (id, account_id, workspace_id),
    FOREIGN KEY (account_id) REFERENCES assistant_account (id),
    FOREIGN KEY (workspace_id) REFERENCES assistant_workspace (id),
    CONSTRAINT chk_asst_connector_grant_time CHECK (
        CHAR_LENGTH(membership_revision) = 36
        AND authorized_at > 0 AND (revoked_at IS NULL OR revoked_at > 0)
    )
);
CREATE INDEX idx_asst_connector_grant_owner
    ON assistant_sandbox_connector_grant (account_id, workspace_id, revoked_at, authorized_at);

-- changeset yuanguangji:assistant-sandbox-connector-2
CREATE TABLE assistant_sandbox_connector_action (
    id VARCHAR(36) PRIMARY KEY,
    account_id VARCHAR(36) NOT NULL,
    workspace_id VARCHAR(36) NOT NULL,
    authorization_id VARCHAR(36) NOT NULL,
    approval_revision VARCHAR(36) NOT NULL,
    idempotency_digest CHAR(64) NOT NULL,
    simulation VARCHAR(16) NOT NULL,
    state VARCHAR(20) NOT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    started_at BIGINT,
    recorded_at BIGINT,
    UNIQUE (account_id, workspace_id, idempotency_digest),
    FOREIGN KEY (authorization_id, account_id, workspace_id)
        REFERENCES assistant_sandbox_connector_grant (id, account_id, workspace_id) ON DELETE CASCADE,
    CONSTRAINT chk_asst_connector_action_state CHECK (
        simulation IN ('acknowledged', 'ack_lost') AND created_at > 0 AND updated_at > 0
        AND ((state IN ('awaiting_approval', 'approved', 'blocked', 'cancelled')
                AND started_at IS NULL AND recorded_at IS NULL)
            OR (state = 'pending_result' AND started_at > 0 AND started_at IS NOT NULL AND recorded_at IS NULL)
            OR (state = 'completed' AND started_at > 0 AND started_at IS NOT NULL
                AND recorded_at > 0 AND recorded_at IS NOT NULL))
    )
);
CREATE INDEX idx_asst_connector_action_dispatch ON assistant_sandbox_connector_action (state, created_at, id);
CREATE INDEX idx_asst_connector_action_owner ON assistant_sandbox_connector_action (account_id, workspace_id, id);

-- changeset yuanguangji:assistant-sandbox-connector-3
ALTER TABLE assistant_deletion_applied DROP CONSTRAINT chk_assistant_deletion_kind,
    ADD CONSTRAINT chk_assistant_deletion_kind CHECK (kind IN (
        'ACCOUNT', 'CONVERSATION', 'SHARE', 'RESOURCE', 'MEMORY', 'MEMORY_OFF',
        'PROJECT_MEM_OFF', 'HISTORY_OFF', 'INSTRUCTIONS_OFF', 'SHARE_LOCK', 'SHARE_UNLOCK',
        'ATTACHMENT', 'RESEARCH_REVOKE', 'REMINDER_DELETE', 'REMINDER_PAUSE',
        'CUSTOM_DELETE', 'CUSTOM_REVISE', 'REMINDER_SHARE', 'COLLAB_GRANT', 'COLLAB_TEXT',
        'ARTIFACT', 'COLLAB_ARTIFACT', 'COLLAB_COMMENT', 'COLLAB_DRAFT', 'WORKSPACE_LEAVE',
        'CONV_RD_LOCK', 'CONV_RD_OPEN', 'CONV_HIST_FENCE',
        'CONNECTOR_REVOKE', 'CONNECTOR_CANCEL', 'CONNECTOR_START'
    ));

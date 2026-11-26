-- liquibase formatted sql

-- changeset yuanguangji:assistant-sandbox-event-reminder-1
CREATE TABLE assistant_sandbox_event_reminder (
    id VARCHAR(36) PRIMARY KEY,
    account_id VARCHAR(36) NOT NULL,
    workspace_id VARCHAR(36) NOT NULL,
    authorization_id VARCHAR(36) NOT NULL,
    title VARCHAR(200) NOT NULL,
    time_zone VARCHAR(64) NOT NULL,
    quiet_start VARCHAR(5),
    quiet_end VARCHAR(5),
    active BOOLEAN NOT NULL,
    config_epoch BIGINT NOT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    FOREIGN KEY (authorization_id, account_id, workspace_id)
        REFERENCES assistant_sandbox_connector_grant (id, account_id, workspace_id) ON DELETE CASCADE,
    CONSTRAINT chk_asst_event_reminder_config CHECK (
        CHAR_LENGTH(title) > 0 AND config_epoch > 0 AND created_at > 0 AND updated_at > 0
        AND ((quiet_start IS NULL AND quiet_end IS NULL)
            OR (quiet_start IS NOT NULL AND quiet_end IS NOT NULL AND quiet_start <> quiet_end))
    )
);
CREATE INDEX idx_asst_event_reminder_scope ON assistant_sandbox_event_reminder (account_id, workspace_id, id);

-- changeset yuanguangji:assistant-sandbox-event-reminder-2
CREATE TABLE assistant_sandbox_event_occurrence (
    id VARCHAR(36) PRIMARY KEY,
    reminder_id VARCHAR(36) NOT NULL,
    operation_id VARCHAR(36) NOT NULL,
    subscription_epoch BIGINT NOT NULL,
    state VARCHAR(12) NOT NULL,
    received_at BIGINT NOT NULL,
    delivery_at BIGINT NOT NULL,
    delivered_at BIGINT,
    read_at BIGINT,
    UNIQUE (reminder_id, operation_id),
    FOREIGN KEY (reminder_id) REFERENCES assistant_sandbox_event_reminder (id) ON DELETE CASCADE,
    CONSTRAINT chk_asst_event_occurrence_state CHECK (
        subscription_epoch > 0 AND received_at > 0 AND delivery_at >= received_at
        AND ((state = 'delivered' AND delivered_at IS NOT NULL AND delivered_at >= delivery_at
                AND (read_at IS NULL OR read_at >= delivered_at))
            OR (state IN ('waiting', 'skipped', 'withdrawn', 'expired') AND delivered_at IS NULL AND read_at IS NULL))
    )
);
CREATE INDEX idx_asst_event_due ON assistant_sandbox_event_occurrence (state, delivery_at, id);

-- changeset yuanguangji:assistant-sandbox-event-reminder-3
ALTER TABLE assistant_deletion_applied DROP CONSTRAINT chk_assistant_deletion_kind,
    ADD CONSTRAINT chk_assistant_deletion_kind CHECK (kind IN (
        'ACCOUNT', 'CONVERSATION', 'SHARE', 'RESOURCE', 'MEMORY', 'MEMORY_OFF',
        'PROJECT_MEM_OFF', 'HISTORY_OFF', 'INSTRUCTIONS_OFF', 'SHARE_LOCK', 'SHARE_UNLOCK',
        'ATTACHMENT', 'RESEARCH_REVOKE', 'REMINDER_DELETE', 'REMINDER_PAUSE',
        'CUSTOM_DELETE', 'CUSTOM_REVISE', 'REMINDER_SHARE', 'COLLAB_GRANT', 'COLLAB_TEXT',
        'ARTIFACT', 'COLLAB_ARTIFACT', 'COLLAB_COMMENT', 'COLLAB_DRAFT', 'WORKSPACE_LEAVE',
        'CONV_RD_LOCK', 'CONV_RD_OPEN', 'CONV_HIST_FENCE',
        'CONNECTOR_REVOKE', 'CONNECTOR_CANCEL', 'CONNECTOR_START',
        'EV_REM_DELETE', 'EV_REM_PAUSE'
    ));

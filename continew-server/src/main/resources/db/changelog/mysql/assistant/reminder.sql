-- liquibase formatted sql

-- changeset yuanguangji:assistant-reminder-1
CREATE TABLE assistant_reminder (
    id VARCHAR(36) PRIMARY KEY,
    account_id VARCHAR(36) NOT NULL,
    workspace_id VARCHAR(36) NOT NULL,
    title VARCHAR(200) NOT NULL,
    starts_at_local VARCHAR(16) NOT NULL,
    time_zone VARCHAR(64) NOT NULL,
    cadence VARCHAR(8) NOT NULL,
    quiet_start VARCHAR(5),
    quiet_end VARCHAR(5),
    status VARCHAR(12) NOT NULL,
    request_digest CHAR(64) NOT NULL,
    config_epoch BIGINT NOT NULL,
    next_slot_day BIGINT,
    next_wake_at BIGINT,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    CONSTRAINT fk_assistant_reminder_account FOREIGN KEY (account_id) REFERENCES assistant_account (id),
    CONSTRAINT fk_assistant_reminder_workspace FOREIGN KEY (workspace_id) REFERENCES assistant_workspace (id),
    CONSTRAINT chk_assistant_reminder_schedule CHECK (
        cadence IN ('once', 'daily', 'weekly') AND config_epoch > 0
        AND ((quiet_start IS NULL AND quiet_end IS NULL)
            OR (quiet_start IS NOT NULL AND quiet_end IS NOT NULL AND quiet_start <> quiet_end))
        AND ((status = 'active' AND next_slot_day IS NOT NULL AND next_wake_at IS NOT NULL)
            OR (status IN ('paused', 'completed', 'expired') AND next_slot_day IS NULL AND next_wake_at IS NULL))
    )
);

-- changeset yuanguangji:assistant-reminder-2
CREATE INDEX idx_assistant_reminder_due ON assistant_reminder (status, next_wake_at, id);
CREATE INDEX idx_assistant_reminder_owner ON assistant_reminder (account_id, workspace_id, created_at, id);

-- changeset yuanguangji:assistant-reminder-3
CREATE TABLE assistant_reminder_occurrence (
    id VARCHAR(36) PRIMARY KEY,
    reminder_id VARCHAR(36) NOT NULL,
    config_epoch BIGINT NOT NULL,
    slot_day BIGINT NOT NULL,
    scheduled_local_at VARCHAR(16) NOT NULL,
    planned_at BIGINT NOT NULL,
    delivery_at BIGINT NOT NULL,
    occurred_at BIGINT NOT NULL,
    outcome VARCHAR(12) NOT NULL,
    read_at BIGINT,
    CONSTRAINT fk_assistant_reminder_occurrence
        FOREIGN KEY (reminder_id) REFERENCES assistant_reminder (id) ON DELETE CASCADE,
    CONSTRAINT uq_assistant_reminder_occurrence UNIQUE (reminder_id, config_epoch, slot_day),
    CONSTRAINT chk_assistant_reminder_occurrence CHECK (
        config_epoch > 0 AND outcome IN ('delivered', 'expired')
        AND delivery_at >= planned_at AND occurred_at >= delivery_at
        AND (read_at IS NULL OR read_at >= occurred_at)
    )
);
CREATE INDEX idx_assistant_reminder_history ON assistant_reminder_occurrence (reminder_id, occurred_at, id);

-- changeset yuanguangji:assistant-reminder-4
ALTER TABLE assistant_deletion_applied DROP CHECK chk_assistant_deletion_kind,
    ADD CONSTRAINT chk_assistant_deletion_kind CHECK (kind IN (
        'ACCOUNT', 'CONVERSATION', 'SHARE', 'RESOURCE', 'MEMORY', 'MEMORY_OFF', 'PROJECT_MEM_OFF',
        'HISTORY_OFF', 'INSTRUCTIONS_OFF', 'SHARE_LOCK', 'SHARE_UNLOCK', 'ATTACHMENT', 'RESEARCH_REVOKE',
        'REMINDER_DELETE', 'REMINDER_PAUSE'
    ));

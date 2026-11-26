-- liquibase formatted sql

-- changeset yuanguangji:assistant-local-proactive-1
CREATE TABLE assistant_proactive_preference (
    account_id VARCHAR(36) NOT NULL,
    workspace_id VARCHAR(36) NOT NULL,
    membership_revision VARCHAR(36) NOT NULL,
    enabled BOOLEAN NOT NULL,
    role VARCHAR(20) NOT NULL,
    notification VARCHAR(8) NOT NULL,
    time_zone VARCHAR(64) NOT NULL,
    quiet_start VARCHAR(5),
    quiet_end VARCHAR(5),
    policy_epoch BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    next_check_at BIGINT,
    PRIMARY KEY (account_id, workspace_id),
    FOREIGN KEY (account_id) REFERENCES assistant_account (id),
    FOREIGN KEY (workspace_id) REFERENCES assistant_workspace (id),
    CONSTRAINT chk_asst_proactive_pref CHECK (
        CHAR_LENGTH(membership_revision) = 36 AND role IN ('reminder_review', 'schedule_planner')
        AND notification IN ('in_app', 'silent') AND policy_epoch > 0 AND updated_at > 0
        AND ((quiet_start IS NULL AND quiet_end IS NULL)
            OR (quiet_start IS NOT NULL AND quiet_end IS NOT NULL AND quiet_start <> quiet_end))
        AND ((enabled = TRUE AND notification = 'in_app' AND next_check_at IS NOT NULL AND next_check_at > 0)
            OR ((enabled = FALSE OR notification = 'silent') AND next_check_at IS NULL))
    )
);
CREATE INDEX idx_asst_proactive_due ON assistant_proactive_preference (enabled, notification, next_check_at);

-- changeset yuanguangji:assistant-local-proactive-2
CREATE TABLE assistant_proactive_suggestion (
    id VARCHAR(36) PRIMARY KEY,
    account_id VARCHAR(36) NOT NULL,
    workspace_id VARCHAR(36) NOT NULL,
    membership_revision VARCHAR(36) NOT NULL,
    policy_epoch BIGINT NOT NULL,
    role VARCHAR(20) NOT NULL,
    local_day BIGINT NOT NULL,
    reminder_id VARCHAR(36) NOT NULL,
    occurrence_id VARCHAR(36),
    source_epoch BIGINT NOT NULL,
    slot_day BIGINT,
    created_at BIGINT NOT NULL,
    dismissed BOOLEAN NOT NULL DEFAULT FALSE,
    UNIQUE (account_id, workspace_id, membership_revision, role, local_day),
    FOREIGN KEY (account_id, workspace_id)
        REFERENCES assistant_proactive_preference (account_id, workspace_id) ON DELETE CASCADE,
    CONSTRAINT chk_asst_proactive_suggestion CHECK (
        CHAR_LENGTH(membership_revision) = 36 AND policy_epoch > 0 AND source_epoch > 0 AND created_at > 0
        AND ((role = 'reminder_review' AND occurrence_id IS NOT NULL AND slot_day IS NULL)
            OR (role = 'schedule_planner' AND occurrence_id IS NULL AND slot_day IS NOT NULL))
    )
);
CREATE INDEX idx_asst_proactive_sug_policy ON assistant_proactive_suggestion (account_id, workspace_id, policy_epoch, local_day);

-- changeset yuanguangji:assistant-local-proactive-3
ALTER TABLE assistant_deletion_applied DROP CHECK chk_assistant_deletion_kind,
    ADD CONSTRAINT chk_assistant_deletion_kind CHECK (kind IN (
        'ACCOUNT', 'CONVERSATION', 'SHARE', 'RESOURCE', 'MEMORY', 'MEMORY_OFF',
        'PROJECT_MEM_OFF', 'HISTORY_OFF', 'INSTRUCTIONS_OFF', 'SHARE_LOCK', 'SHARE_UNLOCK',
        'ATTACHMENT', 'RESEARCH_REVOKE', 'REMINDER_DELETE', 'REMINDER_PAUSE',
        'CUSTOM_DELETE', 'CUSTOM_REVISE', 'REMINDER_SHARE', 'COLLAB_GRANT', 'COLLAB_TEXT',
        'ARTIFACT', 'COLLAB_ARTIFACT', 'COLLAB_COMMENT', 'COLLAB_DRAFT', 'WORKSPACE_LEAVE',
        'CONV_RD_LOCK', 'CONV_RD_OPEN', 'CONV_HIST_FENCE', 'CONNECTOR_REVOKE',
        'CONNECTOR_CANCEL', 'CONNECTOR_START', 'EV_REM_DELETE', 'EV_REM_PAUSE',
        'ORG_ROLE', 'PR_PREF_OFF', 'PR_SUG_DISMISS', 'PR_SUG_ISSUED'
    ));

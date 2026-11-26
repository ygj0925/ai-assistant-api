-- liquibase formatted sql

-- changeset yuanguangji:assistant-organization-1
ALTER TABLE assistant_workspace_member ADD COLUMN organization_authority_revision VARCHAR(36),
    ADD CONSTRAINT chk_assistant_organization_authority CHECK (
        organization_authority_revision IS NULL OR CHAR_LENGTH(organization_authority_revision) = 36
    );

CREATE TABLE assistant_organization (
    workspace_id VARCHAR(36) PRIMARY KEY,
    owner_account_id VARCHAR(36) NOT NULL,
    tenant_id BIGINT NOT NULL,
    name VARCHAR(80) NOT NULL,
    seat_limit INTEGER NOT NULL,
    provision_digest CHAR(64) NOT NULL,
    created_at BIGINT NOT NULL,
    FOREIGN KEY (workspace_id) REFERENCES assistant_workspace (id),
    FOREIGN KEY (owner_account_id) REFERENCES assistant_account (id),
    CONSTRAINT chk_assistant_organization_seats CHECK (seat_limit BETWEEN 1 AND 128 AND CHAR_LENGTH(name) > 0)
);

-- changeset yuanguangji:assistant-organization-2
CREATE TABLE assistant_organization_audit (
    id VARCHAR(36) PRIMARY KEY,
    workspace_id VARCHAR(36) NOT NULL,
    action VARCHAR(24) NOT NULL,
    actor_account_id VARCHAR(36),
    target_account_id VARCHAR(36),
    previous_role VARCHAR(16),
    role VARCHAR(16),
    operator_id BIGINT,
    created_at BIGINT NOT NULL,
    FOREIGN KEY (workspace_id) REFERENCES assistant_organization (workspace_id) ON DELETE CASCADE,
    CONSTRAINT chk_assistant_organization_audit_action CHECK (
        (action = 'provisioned' AND operator_id IS NOT NULL) OR
        (action IN ('member_added', 'role_changed', 'member_revoked') AND operator_id IS NULL)
    )
);
CREATE INDEX idx_assistant_organization_audit_scope ON assistant_organization_audit (workspace_id, created_at, id);
CREATE INDEX idx_assistant_organization_audit_expiry ON assistant_organization_audit (created_at, id);
CREATE INDEX idx_assistant_organization_audit_actor ON assistant_organization_audit (actor_account_id);
CREATE INDEX idx_assistant_organization_audit_target ON assistant_organization_audit (target_account_id);

-- changeset yuanguangji:assistant-organization-3
ALTER TABLE assistant_deletion_applied DROP CHECK chk_assistant_deletion_kind,
    ADD CONSTRAINT chk_assistant_deletion_kind CHECK (kind IN (
        'ACCOUNT', 'CONVERSATION', 'SHARE', 'RESOURCE', 'MEMORY', 'MEMORY_OFF',
        'PROJECT_MEM_OFF', 'HISTORY_OFF', 'INSTRUCTIONS_OFF', 'SHARE_LOCK', 'SHARE_UNLOCK',
        'ATTACHMENT', 'RESEARCH_REVOKE', 'REMINDER_DELETE', 'REMINDER_PAUSE',
        'CUSTOM_DELETE', 'CUSTOM_REVISE', 'REMINDER_SHARE', 'COLLAB_GRANT', 'COLLAB_TEXT',
        'ARTIFACT', 'COLLAB_ARTIFACT', 'COLLAB_COMMENT', 'COLLAB_DRAFT', 'WORKSPACE_LEAVE',
        'CONV_RD_LOCK', 'CONV_RD_OPEN', 'CONV_HIST_FENCE',
        'CONNECTOR_REVOKE', 'CONNECTOR_CANCEL', 'CONNECTOR_START',
        'EV_REM_DELETE', 'EV_REM_PAUSE',
        'ORG_ROLE'
    ));

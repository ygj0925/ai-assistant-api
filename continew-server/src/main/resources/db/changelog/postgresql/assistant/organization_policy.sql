-- liquibase formatted sql

-- changeset yuanguangji:assistant-organization-policy-1
CREATE TABLE assistant_organization_policy (
    workspace_id VARCHAR(36) PRIMARY KEY,
    revision VARCHAR(36) NOT NULL,
    allowed_models TEXT NOT NULL,
    sandbox_connector_allowed BOOLEAN NOT NULL,
    updated_at BIGINT NOT NULL,
    FOREIGN KEY (workspace_id) REFERENCES assistant_organization (workspace_id) ON DELETE CASCADE,
    CONSTRAINT chk_asst_org_policy_rev CHECK (CHAR_LENGTH(revision) = 36),
    CONSTRAINT chk_asst_org_policy_models CHECK (OCTET_LENGTH(allowed_models) BETWEEN 2 AND 4096)
);

-- changeset yuanguangji:assistant-organization-policy-2
ALTER TABLE assistant_run ADD COLUMN organization_policy_revision VARCHAR(36),
    ADD CONSTRAINT chk_asst_run_org_policy_rev CHECK (
        organization_policy_revision IS NULL OR CHAR_LENGTH(organization_policy_revision) = 36
    );
ALTER TABLE assistant_sandbox_connector_action ADD COLUMN organization_policy_revision VARCHAR(36),
    ADD CONSTRAINT chk_asst_action_org_policy_rev CHECK (
        organization_policy_revision IS NULL OR CHAR_LENGTH(organization_policy_revision) = 36
    );

-- changeset yuanguangji:assistant-organization-policy-3
ALTER TABLE assistant_organization_audit DROP CONSTRAINT chk_assistant_organization_audit_action,
    ADD CONSTRAINT chk_assistant_organization_audit_action CHECK (
        (action = 'provisioned' AND operator_id IS NOT NULL) OR
        (action IN ('member_added', 'role_changed', 'member_revoked', 'policy_changed') AND operator_id IS NULL)
    );

-- changeset yuanguangji:assistant-organization-policy-4
ALTER TABLE assistant_deletion_applied DROP CONSTRAINT chk_assistant_deletion_kind,
    ADD CONSTRAINT chk_assistant_deletion_kind CHECK (kind IN (
        'ACCOUNT', 'CONVERSATION', 'SHARE', 'RESOURCE', 'MEMORY', 'MEMORY_OFF',
        'PROJECT_MEM_OFF', 'HISTORY_OFF', 'INSTRUCTIONS_OFF', 'SHARE_LOCK', 'SHARE_UNLOCK',
        'ATTACHMENT', 'RESEARCH_REVOKE', 'REMINDER_DELETE', 'REMINDER_PAUSE',
        'CUSTOM_DELETE', 'CUSTOM_REVISE', 'REMINDER_SHARE', 'COLLAB_GRANT', 'COLLAB_TEXT',
        'ARTIFACT', 'COLLAB_ARTIFACT', 'COLLAB_COMMENT', 'COLLAB_DRAFT', 'WORKSPACE_LEAVE',
        'CONV_RD_LOCK', 'CONV_RD_OPEN', 'CONV_HIST_FENCE', 'CONNECTOR_REVOKE',
        'CONNECTOR_CANCEL', 'CONNECTOR_START', 'EV_REM_DELETE', 'EV_REM_PAUSE',
        'ORG_ROLE', 'PR_PREF_OFF', 'PR_SUG_DISMISS', 'PR_SUG_ISSUED', 'ORG_POLICY'
    ));

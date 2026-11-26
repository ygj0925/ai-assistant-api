-- liquibase formatted sql

-- changeset yuanguangji:assistant-organization-directory-1
CREATE TABLE assistant_organization_directory_user (
    id VARCHAR(36) PRIMARY KEY,
    directory_id VARCHAR(36) NOT NULL,
    workspace_id VARCHAR(36) NOT NULL,
    account_id VARCHAR(36) NOT NULL,
    external_id VARCHAR(36) NOT NULL,
    membership_revision VARCHAR(36) NOT NULL,
    created_at BIGINT NOT NULL,
    disabled_at BIGINT,
    FOREIGN KEY (workspace_id) REFERENCES assistant_organization (workspace_id) ON DELETE CASCADE,
    FOREIGN KEY (account_id) REFERENCES assistant_account (id),
    UNIQUE (workspace_id, directory_id, external_id),
    UNIQUE (workspace_id, account_id, membership_revision),
    CONSTRAINT chk_assistant_org_directory_user CHECK (
        CHAR_LENGTH(id) = 36 AND CHAR_LENGTH(directory_id) = 36 AND
        CHAR_LENGTH(external_id) = 36 AND CHAR_LENGTH(membership_revision) = 36 AND
        (disabled_at IS NULL OR disabled_at >= created_at)
    )
);
CREATE INDEX idx_assistant_org_directory_account ON assistant_organization_directory_user (account_id);

-- changeset yuanguangji:assistant-organization-directory-2
ALTER TABLE assistant_organization_audit ADD COLUMN directory_id VARCHAR(36);
ALTER TABLE assistant_organization_audit DROP CHECK chk_assistant_organization_audit_action;
ALTER TABLE assistant_organization_audit ADD CONSTRAINT chk_assistant_organization_audit_action CHECK (
    (action = 'provisioned' AND operator_id IS NOT NULL AND directory_id IS NULL) OR
    (action IN ('member_added', 'role_changed', 'member_revoked', 'policy_changed', 'model_budget_changed')
        AND operator_id IS NULL AND directory_id IS NULL) OR
    (action = 'directory_deactivated' AND operator_id IS NULL AND actor_account_id IS NULL
        AND directory_id IS NOT NULL AND CHAR_LENGTH(directory_id) = 36)
);

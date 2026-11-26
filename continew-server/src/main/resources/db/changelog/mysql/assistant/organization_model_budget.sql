-- liquibase formatted sql

-- changeset yuanguangji:assistant-organization-model-budget-1
CREATE TABLE assistant_organization_model_budget (
    workspace_id VARCHAR(36) PRIMARY KEY,
    revision VARCHAR(36) NOT NULL,
    monthly_limit_units BIGINT,
    guard_id VARCHAR(36) NOT NULL,
    watermark VARCHAR(36) NOT NULL,
    updated_at BIGINT NOT NULL,
    CONSTRAINT fk_assistant_organization_model_budget
        FOREIGN KEY (workspace_id) REFERENCES assistant_organization (workspace_id) ON DELETE CASCADE,
    CONSTRAINT chk_assistant_organization_model_budget CHECK (
        (monthly_limit_units IS NULL OR monthly_limit_units BETWEEN 0 AND 1000000000)
        AND CHAR_LENGTH(revision) = 36 AND CHAR_LENGTH(guard_id) = 36
        AND CHAR_LENGTH(watermark) = 36 AND updated_at > 0
    )
);

-- changeset yuanguangji:assistant-organization-model-budget-2
ALTER TABLE assistant_organization_audit DROP CHECK chk_assistant_organization_audit_action,
    ADD CONSTRAINT chk_assistant_organization_audit_action CHECK (
        (action = 'provisioned' AND operator_id IS NOT NULL) OR
        (action IN ('member_added', 'role_changed', 'member_revoked', 'policy_changed', 'model_budget_changed')
            AND operator_id IS NULL)
    );

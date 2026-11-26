-- liquibase formatted sql

-- changeset yuanguangji:assistant-organization-sso-1
CREATE TABLE assistant_organization_sso_consent (
    id VARCHAR(36) PRIMARY KEY,
    binding_id VARCHAR(36) NOT NULL UNIQUE,
    created_at BIGINT NOT NULL,
    FOREIGN KEY (binding_id) REFERENCES assistant_organization_directory_user (id) ON DELETE CASCADE,
    CONSTRAINT chk_assistant_org_sso_consent CHECK (CHAR_LENGTH(id) = 36 AND created_at >= 0)
);

CREATE TABLE assistant_organization_sso_session (
    id VARCHAR(36) PRIMARY KEY,
    consent_id VARCHAR(36) NOT NULL,
    token_digest VARCHAR(64) NOT NULL UNIQUE,
    created_at BIGINT NOT NULL,
    expires_at BIGINT NOT NULL,
    FOREIGN KEY (consent_id) REFERENCES assistant_organization_sso_consent (id) ON DELETE CASCADE,
    CONSTRAINT chk_assistant_org_sso_session CHECK (
        CHAR_LENGTH(id) = 36 AND CHAR_LENGTH(token_digest) = 64 AND created_at >= 0 AND
        expires_at > created_at AND expires_at - created_at <= 600000
    )
);
CREATE INDEX idx_assistant_org_sso_expiry ON assistant_organization_sso_session (consent_id, expires_at);

-- changeset yuanguangji:assistant-organization-sso-2
ALTER TABLE assistant_deletion_applied DROP CONSTRAINT chk_assistant_deletion_kind,
    ADD CONSTRAINT chk_assistant_deletion_kind CHECK (kind IN (
        'ACCOUNT', 'CONVERSATION', 'SHARE', 'RESOURCE', 'MEMORY', 'MEMORY_OFF',
        'PROJECT_MEM_OFF', 'HISTORY_OFF', 'INSTRUCTIONS_OFF', 'SHARE_LOCK', 'SHARE_UNLOCK',
        'ATTACHMENT', 'RESEARCH_REVOKE', 'REMINDER_DELETE', 'REMINDER_PAUSE',
        'CUSTOM_DELETE', 'CUSTOM_REVISE', 'REMINDER_SHARE', 'COLLAB_GRANT', 'COLLAB_TEXT',
        'ARTIFACT', 'COLLAB_ARTIFACT', 'COLLAB_COMMENT', 'COLLAB_DRAFT', 'WORKSPACE_LEAVE',
        'CONV_RD_LOCK', 'CONV_RD_OPEN', 'CONV_HIST_FENCE', 'CONNECTOR_REVOKE',
        'CONNECTOR_CANCEL', 'CONNECTOR_START', 'EV_REM_DELETE', 'EV_REM_PAUSE',
        'ORG_ROLE', 'PR_PREF_OFF', 'PR_SUG_DISMISS', 'PR_SUG_ISSUED', 'ORG_POLICY',
        'SITE_REV', 'CAT_REV', 'GUARD_REV', 'ORG_SSO_REV'
    ));

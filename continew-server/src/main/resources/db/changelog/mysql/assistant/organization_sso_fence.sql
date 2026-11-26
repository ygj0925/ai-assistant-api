-- liquibase formatted sql

-- changeset yuanguangji:assistant-organization-sso-fence-1
ALTER TABLE assistant_organization_directory_user ADD COLUMN issuer_digest VARCHAR(64),
    ADD CONSTRAINT chk_assistant_directory_issuer CHECK (
        issuer_digest IS NULL OR CHAR_LENGTH(issuer_digest) = 64
    );
ALTER TABLE assistant_organization_sso_consent ADD COLUMN issuer_digest VARCHAR(64),
    ADD COLUMN link_fence VARCHAR(64),
    ADD CONSTRAINT chk_assistant_sso_link_fence CHECK (
        (issuer_digest IS NULL OR CHAR_LENGTH(issuer_digest) = 64) AND
        (link_fence IS NULL OR CHAR_LENGTH(link_fence) = 64)
    );

-- changeset yuanguangji:assistant-organization-sso-fence-2
ALTER TABLE assistant_deletion_applied DROP CHECK chk_assistant_deletion_kind,
    ADD CONSTRAINT chk_assistant_deletion_kind CHECK (kind IN (
        'ACCOUNT', 'CONVERSATION', 'SHARE', 'RESOURCE', 'MEMORY', 'MEMORY_OFF',
        'PROJECT_MEM_OFF', 'HISTORY_OFF', 'INSTRUCTIONS_OFF', 'SHARE_LOCK', 'SHARE_UNLOCK',
        'ATTACHMENT', 'RESEARCH_REVOKE', 'REMINDER_DELETE', 'REMINDER_PAUSE',
        'CUSTOM_DELETE', 'CUSTOM_REVISE', 'REMINDER_SHARE', 'COLLAB_GRANT', 'COLLAB_TEXT',
        'ARTIFACT', 'COLLAB_ARTIFACT', 'COLLAB_COMMENT', 'COLLAB_DRAFT', 'WORKSPACE_LEAVE',
        'CONV_RD_LOCK', 'CONV_RD_OPEN', 'CONV_HIST_FENCE', 'CONNECTOR_REVOKE',
        'CONNECTOR_CANCEL', 'CONNECTOR_START', 'EV_REM_DELETE', 'EV_REM_PAUSE',
        'ORG_ROLE', 'PR_PREF_OFF', 'PR_SUG_DISMISS', 'PR_SUG_ISSUED', 'ORG_POLICY',
        'SITE_REV', 'CAT_REV', 'GUARD_REV', 'ORG_SSO_REV', 'ORG_SSO_FENCE'
    ));

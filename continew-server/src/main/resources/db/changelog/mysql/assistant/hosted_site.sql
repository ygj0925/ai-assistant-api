-- liquibase formatted sql

-- changeset yuanguangji:assistant-hosted-site-1
CREATE TABLE assistant_hosted_site (
    id VARCHAR(36) PRIMARY KEY,
    revision VARCHAR(36) NOT NULL UNIQUE,
    owner_id VARCHAR(36) NOT NULL,
    workspace_id VARCHAR(36) NOT NULL,
    project_id VARCHAR(36) NOT NULL,
    artifact_id VARCHAR(36) NOT NULL UNIQUE,
    version_id VARCHAR(36) NOT NULL,
    content_digest VARCHAR(64) NOT NULL,
    source_read_revision VARCHAR(36),
    source_policy_revision VARCHAR(64),
    published_at BIGINT NOT NULL,
    CONSTRAINT fk_asst_hosted_site_scope FOREIGN KEY (workspace_id, owner_id, project_id, artifact_id)
        REFERENCES assistant_artifact (workspace_id, account_id, project_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_asst_hosted_site_version FOREIGN KEY (artifact_id, version_id)
        REFERENCES assistant_artifact_version (artifact_id, id) ON DELETE CASCADE,
    CONSTRAINT chk_asst_hosted_site_value
        CHECK (CHAR_LENGTH(id) = 36 AND CHAR_LENGTH(revision) = 36 AND CHAR_LENGTH(content_digest) = 64 AND published_at > 0)
);

-- changeset yuanguangji:assistant-hosted-site-2
ALTER TABLE assistant_deletion_applied DROP CHECK chk_assistant_deletion_kind,
    ADD CONSTRAINT chk_assistant_deletion_kind CHECK (kind IN (
        'ACCOUNT', 'CONVERSATION', 'SHARE', 'RESOURCE', 'MEMORY', 'MEMORY_OFF',
        'PROJECT_MEM_OFF', 'HISTORY_OFF', 'INSTRUCTIONS_OFF', 'SHARE_LOCK', 'SHARE_UNLOCK',
        'ATTACHMENT', 'RESEARCH_REVOKE', 'REMINDER_DELETE', 'REMINDER_PAUSE',
        'CUSTOM_DELETE', 'CUSTOM_REVISE', 'REMINDER_SHARE', 'COLLAB_GRANT', 'COLLAB_TEXT',
        'ARTIFACT', 'COLLAB_ARTIFACT', 'COLLAB_COMMENT', 'COLLAB_DRAFT', 'WORKSPACE_LEAVE',
        'CONV_RD_LOCK', 'CONV_RD_OPEN', 'CONV_HIST_FENCE', 'CONNECTOR_REVOKE',
        'CONNECTOR_CANCEL', 'CONNECTOR_START', 'EV_REM_DELETE', 'EV_REM_PAUSE',
        'ORG_ROLE', 'PR_PREF_OFF', 'PR_SUG_DISMISS', 'PR_SUG_ISSUED', 'ORG_POLICY', 'SITE_REV'
    ));

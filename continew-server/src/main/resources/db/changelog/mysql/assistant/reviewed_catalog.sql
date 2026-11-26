-- liquibase formatted sql

-- changeset yuanguangji:assistant-reviewed-catalog-1
CREATE TABLE assistant_catalog_entry (
    id VARCHAR(36) PRIMARY KEY,
    account_id VARCHAR(36) NOT NULL,
    workspace_id VARCHAR(36) NOT NULL,
    membership_revision VARCHAR(36) NOT NULL,
    kind VARCHAR(12) NOT NULL,
    source_id VARCHAR(36) NOT NULL,
    active_release_id VARCHAR(36),
    FOREIGN KEY (account_id) REFERENCES assistant_account(id),
    FOREIGN KEY (workspace_id) REFERENCES assistant_workspace(id),
    CONSTRAINT chk_asst_catalog_entry_kind CHECK (kind IN ('assistant', 'connector')),
    CONSTRAINT chk_asst_catalog_entry_membership CHECK (CHAR_LENGTH(membership_revision) = 36),
    UNIQUE (id, workspace_id)
);
CREATE INDEX idx_asst_catalog_entry_owner ON assistant_catalog_entry(account_id, workspace_id, membership_revision, id);

-- changeset yuanguangji:assistant-reviewed-catalog-2
CREATE TABLE assistant_catalog_release (
    id VARCHAR(36) PRIMARY KEY,
    entry_id VARCHAR(36) NOT NULL,
    workspace_id VARCHAR(36) NOT NULL,
    source_version_id VARCHAR(36),
    base_release_id VARCHAR(36),
    version_number INTEGER NOT NULL,
    content_digest CHAR(64) NOT NULL,
    title VARCHAR(120) NOT NULL,
    summary VARCHAR(160) NOT NULL,
    state VARCHAR(12) NOT NULL,
    submitted_at BIGINT NOT NULL,
    decided_at BIGINT,
    published_at BIGINT,
    CONSTRAINT chk_asst_catalog_release_state CHECK (
        state IN ('pending', 'published', 'rejected', 'withdrawn', 'superseded')
        AND ((state = 'pending' AND decided_at IS NULL) OR (state <> 'pending' AND decided_at IS NOT NULL))
        AND ((state IN ('published', 'superseded') AND published_at IS NOT NULL)
            OR state IN ('pending', 'rejected', 'withdrawn'))
        AND version_number > 0
    ),
    FOREIGN KEY (entry_id, workspace_id) REFERENCES assistant_catalog_entry(id, workspace_id) ON DELETE CASCADE,
    UNIQUE (id, workspace_id)
);
CREATE INDEX idx_asst_catalog_release_entry ON assistant_catalog_release(entry_id, submitted_at, id);
CREATE INDEX idx_asst_catalog_release_review ON assistant_catalog_release(workspace_id, submitted_at, id);
CREATE INDEX idx_asst_catalog_release_public ON assistant_catalog_release(state, published_at, id);

-- changeset yuanguangji:assistant-reviewed-catalog-3
CREATE TABLE assistant_catalog_audit (
    id VARCHAR(36) PRIMARY KEY,
    release_id VARCHAR(36) NOT NULL,
    workspace_id VARCHAR(36) NOT NULL,
    action VARCHAR(20) NOT NULL,
    operator_id BIGINT,
    created_at BIGINT NOT NULL,
    FOREIGN KEY (release_id, workspace_id) REFERENCES assistant_catalog_release(id, workspace_id) ON DELETE CASCADE,
    CONSTRAINT chk_asst_catalog_audit_action CHECK (
        (action IN ('submitted', 'author_withdrawn') AND operator_id IS NULL)
        OR (action IN ('approved', 'rejected', 'operator_withdrawn', 'superseded') AND operator_id IS NOT NULL)
    )
);
CREATE INDEX idx_asst_catalog_audit_release ON assistant_catalog_audit(release_id, created_at, id);

-- changeset yuanguangji:assistant-reviewed-catalog-4
CREATE TABLE assistant_catalog_report (
    id VARCHAR(36) PRIMARY KEY,
    release_id VARCHAR(36) NOT NULL,
    workspace_id VARCHAR(36) NOT NULL,
    account_id VARCHAR(36) NOT NULL,
    reason VARCHAR(16) NOT NULL,
    created_at BIGINT NOT NULL,
    FOREIGN KEY (release_id, workspace_id) REFERENCES assistant_catalog_release(id, workspace_id) ON DELETE CASCADE,
    FOREIGN KEY (account_id) REFERENCES assistant_account(id),
    CONSTRAINT chk_asst_catalog_report_reason CHECK (reason IN ('spam', 'misleading', 'privacy', 'unsafe', 'other')),
    UNIQUE (release_id, account_id)
);
CREATE INDEX idx_asst_catalog_report_account ON assistant_catalog_report(account_id);

-- changeset yuanguangji:assistant-reviewed-catalog-5
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
        'SITE_REV', 'CAT_REV'
    ));

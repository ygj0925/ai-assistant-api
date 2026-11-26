-- liquibase formatted sql

-- changeset yuanguangji:assistant-guardian-eligibility-1
CREATE TABLE assistant_eligibility_enrollment (
    id VARCHAR(36) PRIMARY KEY,
    workspace_id VARCHAR(36) NOT NULL,
    participant_account_id VARCHAR(36) NOT NULL,
    guardian_account_id VARCHAR(36) NOT NULL,
    participant_revision VARCHAR(36) NOT NULL,
    guardian_revision VARCHAR(36) NOT NULL,
    scenario VARCHAR(24) NOT NULL,
    consent_revision VARCHAR(36) NOT NULL,
    consent_until BIGINT,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    FOREIGN KEY (workspace_id) REFERENCES assistant_workspace(id) ON DELETE CASCADE,
    FOREIGN KEY (participant_account_id) REFERENCES assistant_account(id),
    FOREIGN KEY (guardian_account_id) REFERENCES assistant_account(id),
    UNIQUE (workspace_id, participant_account_id),
    CONSTRAINT chk_asst_eligibility_roles CHECK (participant_account_id <> guardian_account_id),
    CONSTRAINT chk_asst_eligibility_scenario CHECK (
        scenario IN ('guardian_required', 'outside_fixture_region')
        AND (consent_until IS NULL OR (scenario = 'guardian_required' AND consent_until > updated_at))
    ),
    CONSTRAINT chk_asst_eligibility_revision CHECK (
        CHAR_LENGTH(participant_revision) = 36 AND CHAR_LENGTH(guardian_revision) = 36
            AND CHAR_LENGTH(consent_revision) = 36
    )
);
CREATE INDEX idx_asst_eligibility_guardian ON assistant_eligibility_enrollment(workspace_id, guardian_account_id, id);
CREATE INDEX idx_asst_eligibility_participant_account ON assistant_eligibility_enrollment(participant_account_id, workspace_id);
CREATE INDEX idx_asst_eligibility_guardian_account ON assistant_eligibility_enrollment(guardian_account_id, workspace_id);

-- changeset yuanguangji:assistant-guardian-eligibility-2
CREATE TABLE assistant_eligibility_audit (
    id VARCHAR(36) PRIMARY KEY,
    enrollment_id VARCHAR(36) NOT NULL,
    action VARCHAR(16) NOT NULL,
    platform_operator_id BIGINT,
    created_at BIGINT NOT NULL,
    FOREIGN KEY (enrollment_id) REFERENCES assistant_eligibility_enrollment(id) ON DELETE CASCADE,
    CONSTRAINT chk_asst_eligibility_audit CHECK (
        (action = 'provisioned' AND platform_operator_id IS NOT NULL)
        OR (action IN ('authorized', 'withdrawn') AND platform_operator_id IS NULL)
    )
);
CREATE INDEX idx_asst_eligibility_audit_history ON assistant_eligibility_audit(enrollment_id, created_at, id);

-- changeset yuanguangji:assistant-guardian-eligibility-3
ALTER TABLE assistant_sandbox_connector_action ADD COLUMN eligibility_revision VARCHAR(36),
    ADD CONSTRAINT chk_asst_connector_eligibility_revision CHECK (
        eligibility_revision IS NULL OR CHAR_LENGTH(eligibility_revision) = 36
    );

-- changeset yuanguangji:assistant-guardian-eligibility-4
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
        'SITE_REV', 'CAT_REV', 'GUARD_REV'
    ));

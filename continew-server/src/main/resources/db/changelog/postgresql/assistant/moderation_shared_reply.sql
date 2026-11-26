-- liquibase formatted sql

-- changeset yuanguangji:assistant-moderation-shared-reply-1
ALTER TABLE assistant_content_report
    ALTER COLUMN conversation_id DROP NOT NULL,
    ADD COLUMN target_owner_account_id VARCHAR(36),
    ADD COLUMN reply_index SMALLINT,
    ADD CONSTRAINT chk_asst_report_shared_target CHECK (
        (target_type = 'shared_reply' AND conversation_id IS NOT NULL
            AND target_owner_account_id IS NOT NULL AND reply_index IS NOT NULL AND reply_index BETWEEN 0 AND 63)
        OR (target_type <> 'shared_reply' AND reply_index IS NULL
            AND (target_type <> 'assistant_reply' OR conversation_id IS NOT NULL))
    );

-- changeset yuanguangji:assistant-moderation-shared-reply-2
ALTER TABLE assistant_content_report DROP CONSTRAINT assistant_content_report_status_check,
    ADD CONSTRAINT chk_asst_report_disposition CHECK (
        status IN ('open', 'dismissed', 'escalated', 'target_unavailable', 'withdrawn')
    );

-- changeset yuanguangji:assistant-moderation-shared-reply-3
ALTER TABLE assistant_report_audit
    DROP CONSTRAINT assistant_report_audit_decision_check,
    DROP CONSTRAINT assistant_report_audit_resulting_status_check,
    ADD CONSTRAINT chk_asst_report_audit_decision CHECK (
        decision IN ('dismiss', 'escalate', 'withdraw')
    ),
    ADD CONSTRAINT chk_asst_report_audit_disposition CHECK (
        resulting_status IN ('dismissed', 'escalated', 'target_unavailable', 'withdrawn')
    );

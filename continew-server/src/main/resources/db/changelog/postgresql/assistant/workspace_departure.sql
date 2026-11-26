-- liquibase formatted sql

-- changeset yuanguangji:assistant-workspace-departure-1
ALTER TABLE assistant_workspace_member
    ADD COLUMN membership_revision VARCHAR(36) NOT NULL DEFAULT '00000000-0000-0000-0000-000000000000',
    ADD CONSTRAINT chk_assistant_workspace_membership_revision CHECK (CHAR_LENGTH(membership_revision) = 36);

-- changeset yuanguangji:assistant-workspace-departure-2
ALTER TABLE assistant_deletion_applied DROP CONSTRAINT chk_assistant_deletion_kind,
    ADD CONSTRAINT chk_assistant_deletion_kind CHECK (kind IN (
        'ACCOUNT', 'CONVERSATION', 'SHARE', 'RESOURCE', 'MEMORY', 'MEMORY_OFF',
        'PROJECT_MEM_OFF', 'HISTORY_OFF', 'INSTRUCTIONS_OFF', 'SHARE_LOCK', 'SHARE_UNLOCK',
        'ATTACHMENT', 'RESEARCH_REVOKE', 'REMINDER_DELETE', 'REMINDER_PAUSE',
        'CUSTOM_DELETE', 'CUSTOM_REVISE', 'REMINDER_SHARE', 'COLLAB_GRANT', 'COLLAB_TEXT',
        'ARTIFACT', 'COLLAB_ARTIFACT', 'COLLAB_COMMENT', 'COLLAB_DRAFT', 'WORKSPACE_LEAVE'
    ));

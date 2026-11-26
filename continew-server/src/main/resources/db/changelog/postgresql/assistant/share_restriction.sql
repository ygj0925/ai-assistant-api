-- liquibase formatted sql

-- changeset yuanguangji:assistant-share-restriction-1
ALTER TABLE assistant_conversation ADD COLUMN sharing_restricted BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN sharing_policy_revision CHAR(64);

-- changeset yuanguangji:assistant-share-restriction-2
ALTER TABLE assistant_deletion_applied DROP CONSTRAINT chk_assistant_deletion_kind,
    ADD CONSTRAINT chk_assistant_deletion_kind CHECK (kind IN (
        'ACCOUNT', 'CONVERSATION', 'SHARE', 'RESOURCE', 'MEMORY', 'MEMORY_OFF', 'PROJECT_MEM_OFF',
        'HISTORY_OFF', 'INSTRUCTIONS_OFF', 'SHARE_LOCK', 'SHARE_UNLOCK'
    ));

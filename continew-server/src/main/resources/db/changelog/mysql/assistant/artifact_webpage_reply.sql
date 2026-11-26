-- liquibase formatted sql

-- changeset yuanguangji:assistant-artifact-webpage-reply-1
ALTER TABLE assistant_artifact DROP CHECK chk_assistant_artifact_scope,
    ADD CONSTRAINT chk_assistant_artifact_scope CHECK (
        format IN ('text', 'markdown', 'sheet', 'slides', 'code', 'webpage') AND (
            (source_conversation_id IS NULL AND source_message_id IS NULL AND project_id IS NOT NULL)
            OR (format IN ('text', 'markdown', 'webpage') AND source_conversation_id IS NOT NULL AND source_message_id IS NOT NULL)
        )
    );

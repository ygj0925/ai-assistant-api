-- liquibase formatted sql

-- changeset yuanguangji:assistant-artifact-completion-source-1
ALTER TABLE assistant_artifact ADD COLUMN source_placement_revision VARCHAR(64),
    DROP CONSTRAINT chk_assistant_artifact_scope,
    ADD CONSTRAINT chk_assistant_artifact_scope CHECK (
        format IN ('text', 'markdown', 'sheet', 'slides', 'code', 'webpage', 'image')
        AND (
            (source_conversation_id IS NULL AND source_message_id IS NULL AND project_id IS NOT NULL)
            OR (source_conversation_id IS NOT NULL AND source_message_id IS NOT NULL
                AND (format <> 'image' OR project_id IS NOT NULL))
        )
        AND (source_placement_revision IS NULL OR (source_conversation_id IS NOT NULL
            AND format IN ('sheet', 'slides', 'code', 'image') AND CHAR_LENGTH(source_placement_revision) = 64))
    );

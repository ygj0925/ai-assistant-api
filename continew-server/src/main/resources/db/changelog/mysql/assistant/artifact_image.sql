-- liquibase formatted sql

-- changeset yuanguangji:assistant-artifact-image-1
ALTER TABLE assistant_artifact DROP CHECK chk_assistant_artifact_scope,
    ADD CONSTRAINT chk_assistant_artifact_scope CHECK (
        format IN ('text', 'markdown', 'sheet', 'slides', 'code', 'webpage', 'image') AND (
            (source_conversation_id IS NULL AND source_message_id IS NULL AND project_id IS NOT NULL)
            OR (format IN ('text', 'markdown', 'webpage') AND source_conversation_id IS NOT NULL AND source_message_id IS NOT NULL)
        )
    );

-- changeset yuanguangji:assistant-artifact-image-2
ALTER TABLE assistant_attachment ADD CONSTRAINT uq_assistant_attachment_full_scope
    UNIQUE (workspace_id, account_id, project_id, id);

CREATE TABLE assistant_artifact_image_source (
    artifact_id VARCHAR(36) NOT NULL,
    version_id VARCHAR(36) NOT NULL,
    workspace_id VARCHAR(36) NOT NULL,
    account_id VARCHAR(36) NOT NULL,
    project_id VARCHAR(36) NOT NULL,
    attachment_id VARCHAR(36) NOT NULL,
    PRIMARY KEY (artifact_id, version_id),
    CONSTRAINT fk_assistant_artifact_image_scope
        FOREIGN KEY (workspace_id, account_id, project_id, artifact_id)
        REFERENCES assistant_artifact (workspace_id, account_id, project_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_assistant_artifact_image_version FOREIGN KEY (artifact_id, version_id)
        REFERENCES assistant_artifact_version (artifact_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_assistant_artifact_image_attachment
        FOREIGN KEY (workspace_id, account_id, project_id, attachment_id)
        REFERENCES assistant_attachment (workspace_id, account_id, project_id, id)
);

-- changeset yuanguangji:assistant-artifact-image-3
ALTER TABLE assistant_attachment_grant ADD COLUMN grant_scope VARCHAR(48) NOT NULL DEFAULT 'attachment';

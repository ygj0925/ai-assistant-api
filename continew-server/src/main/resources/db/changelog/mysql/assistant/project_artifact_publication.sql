-- liquibase formatted sql

-- changeset yuanguangji:assistant-project-artifact-publication-1
ALTER TABLE assistant_artifact ADD CONSTRAINT uq_assistant_artifact_full_scope
    UNIQUE (workspace_id, account_id, project_id, id);
ALTER TABLE assistant_artifact_version ADD CONSTRAINT uq_assistant_artifact_version_id
    UNIQUE (artifact_id, id);

CREATE TABLE assistant_project_artifact_publication (
    id VARCHAR(36) PRIMARY KEY,
    workspace_id VARCHAR(36) NOT NULL,
    owner_id VARCHAR(36) NOT NULL,
    project_id VARCHAR(36) NOT NULL,
    artifact_id VARCHAR(36) NOT NULL,
    version_id VARCHAR(36) NOT NULL,
    source_policy_revision VARCHAR(64),
    published_at BIGINT NOT NULL,
    CONSTRAINT fk_assistant_collab_publication_scope
        FOREIGN KEY (workspace_id, owner_id, project_id, artifact_id)
        REFERENCES assistant_artifact (workspace_id, account_id, project_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_assistant_collab_publication_version FOREIGN KEY (artifact_id, version_id)
        REFERENCES assistant_artifact_version (artifact_id, id) ON DELETE CASCADE
);
CREATE INDEX idx_assistant_collab_publication_scope
    ON assistant_project_artifact_publication (workspace_id, owner_id, project_id, published_at, id);

-- changeset yuanguangji:assistant-project-artifact-publication-2
ALTER TABLE assistant_deletion_applied DROP CHECK chk_assistant_deletion_kind,
    ADD CONSTRAINT chk_assistant_deletion_kind CHECK (kind IN (
        'ACCOUNT', 'CONVERSATION', 'SHARE', 'RESOURCE', 'MEMORY', 'MEMORY_OFF',
        'PROJECT_MEM_OFF', 'HISTORY_OFF', 'INSTRUCTIONS_OFF', 'SHARE_LOCK', 'SHARE_UNLOCK',
        'ATTACHMENT', 'RESEARCH_REVOKE', 'REMINDER_DELETE', 'REMINDER_PAUSE',
        'CUSTOM_DELETE', 'CUSTOM_REVISE', 'REMINDER_SHARE', 'COLLAB_GRANT', 'COLLAB_TEXT',
        'ARTIFACT', 'COLLAB_ARTIFACT'
    ));

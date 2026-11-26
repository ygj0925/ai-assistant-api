-- liquibase formatted sql

-- changeset yuanguangji:assistant-project-memory-source-1
CREATE TABLE assistant_run_project_memory_source (
    run_id VARCHAR(36) NOT NULL,
    source_order SMALLINT NOT NULL,
    account_id VARCHAR(36) NOT NULL,
    workspace_id VARCHAR(36) NOT NULL,
    conversation_id VARCHAR(36) NOT NULL,
    project_id VARCHAR(36) NOT NULL,
    entry_id VARCHAR(36),
    memory_epoch BIGINT NOT NULL,
    membership_revision VARCHAR(36) NOT NULL,
    read_revision CHAR(64),
    placement_revision CHAR(64),
    PRIMARY KEY (run_id, source_order),
    CONSTRAINT chk_assistant_project_memory_source CHECK (source_order BETWEEN 1 AND 3 AND memory_epoch > 0),
    FOREIGN KEY (run_id, workspace_id, account_id, conversation_id)
        REFERENCES assistant_run (id, workspace_id, account_id, conversation_id) ON DELETE CASCADE,
    FOREIGN KEY (workspace_id, account_id, project_id)
        REFERENCES assistant_project (workspace_id, account_id, id)
);

CREATE INDEX idx_assistant_project_memory_source_conversation
    ON assistant_run_project_memory_source (account_id, workspace_id, conversation_id);
CREATE INDEX idx_assistant_project_memory_source_entry
    ON assistant_run_project_memory_source (account_id, workspace_id, entry_id);

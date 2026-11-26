-- liquibase formatted sql

-- changeset yuanguangji:assistant-analysis-1
ALTER TABLE assistant_project_resource
    ADD CONSTRAINT uq_assistant_project_material_scope UNIQUE (workspace_id, account_id, project_id, id);

-- changeset yuanguangji:assistant-analysis-2
CREATE TABLE assistant_data_analysis (
    id VARCHAR(36) PRIMARY KEY,
    workspace_id VARCHAR(36) NOT NULL,
    account_id VARCHAR(36) NOT NULL,
    project_id VARCHAR(36) NOT NULL,
    resource_id VARCHAR(36) NOT NULL,
    value_column VARCHAR(120) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    row_count INTEGER NOT NULL,
    value_count INTEGER NOT NULL,
    min_value VARCHAR(64) NOT NULL,
    max_value VARCHAR(64) NOT NULL,
    sum_value VARCHAR(64) NOT NULL,
    mean_value VARCHAR(64) NOT NULL,
    series_json TEXT NOT NULL,
    artifact_csv TEXT NOT NULL,
    created_at BIGINT NOT NULL,
    duration_millis BIGINT NOT NULL,
    algorithm_version VARCHAR(32) NOT NULL,
    CONSTRAINT fk_assistant_analysis_resource
        FOREIGN KEY (workspace_id, account_id, project_id, resource_id)
        REFERENCES assistant_project_resource (workspace_id, account_id, project_id, id) ON DELETE CASCADE,
    CONSTRAINT uq_assistant_analysis_input
        UNIQUE (workspace_id, account_id, project_id, resource_id, value_column, algorithm_version),
    CONSTRAINT chk_assistant_analysis_budget CHECK (
        row_count BETWEEN 1 AND 256 AND value_count BETWEEN 1 AND row_count AND duration_millis BETWEEN 0 AND 249
    )
);

-- changeset yuanguangji:assistant-analysis-3
CREATE INDEX idx_assistant_analysis_erasure
    ON assistant_data_analysis (resource_id, workspace_id, account_id);

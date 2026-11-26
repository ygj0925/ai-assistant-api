-- liquibase formatted sql

-- changeset yuanguangji:assistant-organization-usage-1
CREATE INDEX idx_asst_run_workspace_usage ON assistant_run (workspace_id, created_at, id);

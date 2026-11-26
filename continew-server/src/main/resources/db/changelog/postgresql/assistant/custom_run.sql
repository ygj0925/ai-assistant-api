-- liquibase formatted sql

-- changeset yuanguangji:assistant-custom-run-1
ALTER TABLE assistant_run
    ADD COLUMN custom_assistant_id VARCHAR(36),
    ADD COLUMN custom_version_id VARCHAR(36),
    ADD CONSTRAINT chk_assistant_run_custom_version CHECK (
        (custom_assistant_id IS NULL AND custom_version_id IS NULL)
        OR (custom_assistant_id IS NOT NULL AND custom_version_id IS NOT NULL)
    );

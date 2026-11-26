-- liquibase formatted sql

-- changeset yuanguangji:assistant-research-checkpoint-1
ALTER TABLE assistant_research_source
    ADD COLUMN verified_revision INTEGER,
    ADD COLUMN verified_at BIGINT,
    ADD CONSTRAINT chk_assistant_research_verification CHECK (
        (verified_revision IS NULL AND verified_at IS NULL)
        OR (
            verified_revision IS NOT NULL AND verified_at IS NOT NULL
            AND verified_revision BETWEEN 1 AND revision AND verified_at > 0
        )
    );

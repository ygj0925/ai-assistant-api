-- liquibase formatted sql

-- changeset yuanguangji:assistant-model-context-1
ALTER TABLE assistant_run
    ADD COLUMN context_budget INTEGER,
    ADD COLUMN context_used INTEGER,
    ADD COLUMN context_turns INTEGER,
    ADD COLUMN context_trimmed BOOLEAN,
    ADD COLUMN fallback_from_model_id VARCHAR(128);

-- changeset yuanguangji:assistant-model-context-2
ALTER TABLE assistant_run
    ADD CONSTRAINT chk_assistant_run_context CHECK (
        (context_budget IS NULL AND context_used IS NULL AND context_turns IS NULL AND context_trimmed IS NULL)
        OR (context_budget IS NOT NULL AND context_used IS NOT NULL
            AND context_turns IS NOT NULL AND context_trimmed IS NOT NULL
            AND context_budget BETWEEN 8000 AND 32768
            AND context_used BETWEEN 1 AND context_budget
            AND context_turns BETWEEN 1 AND 32)
    );

-- changeset yuanguangji:assistant-model-context-3
CREATE UNIQUE INDEX uq_assistant_run_response ON assistant_run (response_message_id);

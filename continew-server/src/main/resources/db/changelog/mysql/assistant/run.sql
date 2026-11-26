-- liquibase formatted sql

-- changeset yuanguangji:assistant-run-1
CREATE UNIQUE INDEX uq_assistant_conversation_scope
    ON assistant_conversation (workspace_id, account_id, id);

-- changeset yuanguangji:assistant-run-2
CREATE TABLE assistant_run (
    id VARCHAR(36) PRIMARY KEY,
    workspace_id VARCHAR(36) NOT NULL,
    account_id VARCHAR(36) NOT NULL,
    conversation_id VARCHAR(36) NOT NULL,
    prompt_message_id VARCHAR(36) NOT NULL,
    response_message_id VARCHAR(36),
    idempotency_key CHAR(64) NOT NULL,
    request_digest CHAR(64) NOT NULL,
    state VARCHAR(16) NOT NULL CHECK (state IN ('queued', 'running', 'completed', 'cancelled', 'failed')),
    last_sequence BIGINT NOT NULL DEFAULT 0,
    first_sequence BIGINT NOT NULL DEFAULT 1,
    content MEDIUMTEXT NOT NULL,
    model_id VARCHAR(128) NOT NULL,
    model_version VARCHAR(255) NOT NULL,
    error_code VARCHAR(64),
    error_message VARCHAR(512),
    lease_token VARCHAR(64),
    lease_until BIGINT,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    finished_at BIGINT,
    UNIQUE (account_id, idempotency_key),
    UNIQUE (id, account_id),
    CHECK (last_sequence BETWEEN 0 AND 9007199254740991),
    CHECK (first_sequence BETWEEN 1 AND last_sequence + 1),
    FOREIGN KEY (workspace_id, account_id, conversation_id)
        REFERENCES assistant_conversation (workspace_id, account_id, id),
    FOREIGN KEY (conversation_id, prompt_message_id) REFERENCES assistant_chat_message (conversation_id, id),
    FOREIGN KEY (conversation_id, response_message_id) REFERENCES assistant_chat_message (conversation_id, id)
);

-- changeset yuanguangji:assistant-run-3
CREATE INDEX idx_assistant_run_queue
    ON assistant_run (state, created_at, id);

-- changeset yuanguangji:assistant-run-4
CREATE INDEX idx_assistant_run_lease
    ON assistant_run (state, lease_until, id);

-- changeset yuanguangji:assistant-run-5
CREATE TABLE assistant_run_event (
    run_id VARCHAR(36) NOT NULL,
    sequence BIGINT NOT NULL CHECK (sequence BETWEEN 1 AND 9007199254740991),
    type VARCHAR(24) NOT NULL
        CHECK (type IN ('run.started', 'message.delta', 'run.completed', 'run.cancelled', 'run.failed')),
    delta MEDIUMTEXT,
    message_id VARCHAR(36),
    error_code VARCHAR(64),
    error_message VARCHAR(512),
    created_at BIGINT NOT NULL,
    PRIMARY KEY (run_id, sequence),
    FOREIGN KEY (run_id) REFERENCES assistant_run (id) ON DELETE CASCADE
);

-- changeset yuanguangji:assistant-run-6
CREATE INDEX idx_assistant_run_event_age
    ON assistant_run_event (created_at, run_id, sequence);

-- changeset yuanguangji:assistant-run-7
CREATE TABLE assistant_usage_account (
    account_id VARCHAR(36) PRIMARY KEY,
    limit_units BIGINT NOT NULL CHECK (limit_units >= 0),
    reserved_units BIGINT NOT NULL DEFAULT 0 CHECK (reserved_units >= 0),
    used_units BIGINT NOT NULL DEFAULT 0 CHECK (used_units >= 0),
    CHECK (used_units <= limit_units AND reserved_units <= limit_units - used_units),
    FOREIGN KEY (account_id) REFERENCES assistant_account (id)
);

-- changeset yuanguangji:assistant-run-8
CREATE TABLE assistant_run_charge (
    run_id VARCHAR(36) PRIMARY KEY,
    account_id VARCHAR(36) NOT NULL,
    reserved_units BIGINT NOT NULL CHECK (reserved_units >= 0),
    settled_units BIGINT CHECK (settled_units >= 0),
    FOREIGN KEY (run_id, account_id) REFERENCES assistant_run (id, account_id),
    FOREIGN KEY (account_id) REFERENCES assistant_usage_account (account_id)
);

-- changeset yuanguangji:assistant-run-9
CREATE INDEX idx_assistant_run_charge_account
    ON assistant_run_charge (account_id, run_id);

-- changeset yuanguangji:assistant-run-10
CREATE TABLE assistant_subscription_order (
    id VARCHAR(36) PRIMARY KEY,
    workspace_id VARCHAR(36) NOT NULL,
    account_id VARCHAR(36) NOT NULL,
    channel VARCHAR(32) NOT NULL,
    idempotency_key CHAR(64) NOT NULL,
    channel_order_digest CHAR(64),
    product_id VARCHAR(128) NOT NULL,
    state VARCHAR(32) NOT NULL,
    currency CHAR(3),
    amount_minor BIGINT CHECK (amount_minor >= 0),
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    UNIQUE (channel, account_id, idempotency_key),
    UNIQUE (channel, channel_order_digest),
    UNIQUE (channel, id),
    FOREIGN KEY (workspace_id) REFERENCES assistant_workspace (id),
    FOREIGN KEY (account_id) REFERENCES assistant_account (id)
);

-- changeset yuanguangji:assistant-run-11
CREATE INDEX idx_assistant_subscription_order_owner
    ON assistant_subscription_order (workspace_id, account_id, created_at, id);

-- changeset yuanguangji:assistant-run-12
CREATE TABLE assistant_subscription_event (
    id VARCHAR(36) PRIMARY KEY,
    channel VARCHAR(32) NOT NULL,
    delivery_digest CHAR(64) NOT NULL,
    payload_digest CHAR(64) NOT NULL,
    order_id VARCHAR(36),
    channel_order_digest CHAR(64),
    event_type VARCHAR(64) NOT NULL,
    occurred_at BIGINT,
    received_at BIGINT NOT NULL,
    processing_state VARCHAR(16) NOT NULL
        CHECK (processing_state IN ('pending', 'applied', 'ignored', 'failed')),
    processed_at BIGINT,
    UNIQUE (channel, delivery_digest),
    FOREIGN KEY (channel, order_id) REFERENCES assistant_subscription_order (channel, id)
);

-- changeset yuanguangji:assistant-run-13
CREATE INDEX idx_assistant_subscription_event_pending
    ON assistant_subscription_event (processing_state, received_at, id);

-- changeset yuanguangji:assistant-run-14
CREATE INDEX idx_assistant_subscription_event_order
    ON assistant_subscription_event (channel, channel_order_digest, received_at);

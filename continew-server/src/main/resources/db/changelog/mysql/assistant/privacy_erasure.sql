-- liquibase formatted sql

-- changeset yuanguangji:assistant-privacy-erasure-1
ALTER TABLE assistant_account ADD COLUMN deleted_at BIGINT;

-- changeset yuanguangji:assistant-privacy-erasure-2
CREATE TABLE assistant_deletion_applied (
    event_id CHAR(64) PRIMARY KEY,
    kind VARCHAR(16) NOT NULL CHECK (kind IN ('ACCOUNT', 'CONVERSATION')),
    applied_at BIGINT NOT NULL
);

-- changeset yuanguangji:assistant-privacy-erasure-3
CREATE TABLE assistant_deletion_binding (
    binding_id SMALLINT PRIMARY KEY CHECK (binding_id = 1),
    journal_id VARCHAR(36) NOT NULL,
    bound_at BIGINT NOT NULL
);


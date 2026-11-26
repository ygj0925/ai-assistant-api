-- liquibase formatted sql

-- changeset yuanguangji:assistant-account-1
CREATE TABLE assistant_account (
    id VARCHAR(36) PRIMARY KEY,
    username VARCHAR(32) NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    locale VARCHAR(35) NOT NULL,
    theme VARCHAR(8) NOT NULL,
    created_at BIGINT NOT NULL
);

-- changeset yuanguangji:assistant-account-2
CREATE TABLE assistant_workspace (
    id VARCHAR(36) PRIMARY KEY,
    personal_owner_id VARCHAR(36) UNIQUE,
    created_at BIGINT NOT NULL,
    FOREIGN KEY (personal_owner_id) REFERENCES assistant_account (id)
);

-- changeset yuanguangji:assistant-account-3
CREATE TABLE assistant_workspace_member (
    workspace_id VARCHAR(36) NOT NULL,
    account_id VARCHAR(36) NOT NULL,
    membership_role VARCHAR(16) NOT NULL,
    PRIMARY KEY (workspace_id, account_id),
    FOREIGN KEY (workspace_id) REFERENCES assistant_workspace (id),
    FOREIGN KEY (account_id) REFERENCES assistant_account (id)
);

-- changeset yuanguangji:assistant-account-4
CREATE INDEX idx_assistant_workspace_member_account ON assistant_workspace_member (account_id);

-- changeset yuanguangji:assistant-account-5
CREATE TABLE assistant_session (
    id VARCHAR(36) PRIMARY KEY,
    account_id VARCHAR(36) NOT NULL,
    token_digest VARCHAR(64) NOT NULL UNIQUE,
    created_at BIGINT NOT NULL,
    expires_at BIGINT NOT NULL,
    FOREIGN KEY (account_id) REFERENCES assistant_account (id)
);

-- changeset yuanguangji:assistant-account-6
CREATE INDEX idx_assistant_session_account_expiry ON assistant_session (account_id, expires_at);

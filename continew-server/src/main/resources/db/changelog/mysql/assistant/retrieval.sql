-- liquibase formatted sql

-- changeset yuanguangji:assistant-retrieval-1
CREATE TABLE assistant_retrieval_source (
    id VARCHAR(36) PRIMARY KEY,
    title VARCHAR(256) NOT NULL,
    source_url VARCHAR(2048) NOT NULL,
    claim_text VARCHAR(1200) NOT NULL,
    evidence_text VARCHAR(2000) NOT NULL,
    search_text VARCHAR(2048) NOT NULL,
    accessed_at BIGINT NOT NULL,
    expires_at BIGINT NOT NULL,
    available BOOLEAN NOT NULL DEFAULT TRUE,
    CONSTRAINT chk_assistant_retrieval_window CHECK (accessed_at > 0 AND expires_at > accessed_at)
);

-- changeset yuanguangji:assistant-retrieval-2
CREATE TABLE assistant_retrieval_answer (
    id VARCHAR(36) PRIMARY KEY,
    workspace_id VARCHAR(36) NOT NULL,
    account_id VARCHAR(36) NOT NULL,
    created_at BIGINT NOT NULL,
    FOREIGN KEY (workspace_id) REFERENCES assistant_workspace (id),
    FOREIGN KEY (account_id) REFERENCES assistant_account (id)
);

-- changeset yuanguangji:assistant-retrieval-3
CREATE INDEX idx_assistant_retrieval_answer_owner
    ON assistant_retrieval_answer (account_id, created_at);

-- changeset yuanguangji:assistant-retrieval-4
CREATE TABLE assistant_retrieval_citation (
    id VARCHAR(36) PRIMARY KEY,
    answer_id VARCHAR(36) NOT NULL,
    source_id VARCHAR(36),
    ordinal SMALLINT NOT NULL CHECK (ordinal BETWEEN 0 AND 5),
    title VARCHAR(256) NOT NULL,
    source_url VARCHAR(2048) NOT NULL,
    claim_text VARCHAR(1200) NOT NULL,
    evidence_text VARCHAR(2000) NOT NULL,
    accessed_at BIGINT NOT NULL,
    UNIQUE (answer_id, ordinal),
    FOREIGN KEY (answer_id) REFERENCES assistant_retrieval_answer (id) ON DELETE CASCADE,
    FOREIGN KEY (source_id) REFERENCES assistant_retrieval_source (id) ON DELETE SET NULL
);

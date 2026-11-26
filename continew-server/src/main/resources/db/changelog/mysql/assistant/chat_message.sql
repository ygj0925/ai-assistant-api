-- liquibase formatted sql

-- changeset yuanguangji:assistant-chat-message-1
CREATE TABLE assistant_chat_message (
    id VARCHAR(36) PRIMARY KEY,
    conversation_id VARCHAR(36) NOT NULL,
    parent_id VARCHAR(36),
    role VARCHAR(16) NOT NULL CHECK (role IN ('user', 'assistant')),
    content MEDIUMTEXT NOT NULL,
    created_at BIGINT NOT NULL,
    UNIQUE (conversation_id, id),
    FOREIGN KEY (conversation_id) REFERENCES assistant_conversation (id) ON DELETE CASCADE,
    FOREIGN KEY (conversation_id, parent_id)
        REFERENCES assistant_chat_message (conversation_id, id) ON DELETE CASCADE
);

-- changeset yuanguangji:assistant-chat-message-2
CREATE INDEX idx_assistant_chat_message_history
    ON assistant_chat_message (conversation_id, created_at, id);

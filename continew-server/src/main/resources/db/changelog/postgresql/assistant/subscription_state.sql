-- liquibase formatted sql

-- changeset yuanguangji:assistant-subscription-state-1
ALTER TABLE assistant_subscription_order
    ADD COLUMN provider_revision BIGINT NOT NULL DEFAULT 0 CHECK (provider_revision >= 0),
    ADD COLUMN entitled_until BIGINT CHECK (entitled_until > 0);

-- changeset yuanguangji:assistant-subscription-state-2
ALTER TABLE assistant_subscription_event
    ADD COLUMN provider_revision BIGINT CHECK (provider_revision > 0),
    ADD COLUMN entitled_until BIGINT CHECK (entitled_until > 0);

-- changeset yuanguangji:assistant-subscription-state-3
CREATE INDEX idx_assistant_subscription_entitlement
    ON assistant_subscription_order (channel, workspace_id, account_id, state, entitled_until);

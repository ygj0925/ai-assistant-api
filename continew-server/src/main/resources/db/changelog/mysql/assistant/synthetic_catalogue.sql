-- liquibase formatted sql

-- changeset yuanguangji:assistant-synthetic-catalogue-1
CREATE TABLE assistant_synthetic_catalogue_product (
    id VARCHAR(36) PRIMARY KEY,
    title VARCHAR(140) NOT NULL,
    description VARCHAR(500) NOT NULL,
    category VARCHAR(80) NOT NULL,
    search_text VARCHAR(1024) NOT NULL,
    CONSTRAINT chk_asst_synth_product_text CHECK (
        CHAR_LENGTH(title) > 0 AND CHAR_LENGTH(category) > 0
    )
);

-- changeset yuanguangji:assistant-synthetic-catalogue-2
CREATE TABLE assistant_synthetic_catalogue_offer (
    id VARCHAR(36) PRIMARY KEY,
    product_id VARCHAR(36) NOT NULL,
    state VARCHAR(12) NOT NULL,
    price_minor BIGINT,
    price_scale SMALLINT,
    currency CHAR(3),
    merchant_name VARCHAR(120),
    merchant_url VARCHAR(256),
    observed_at BIGINT NOT NULL,
    expires_at BIGINT,
    UNIQUE (product_id, observed_at),
    FOREIGN KEY (product_id) REFERENCES assistant_synthetic_catalogue_product (id) ON DELETE CASCADE,
    CONSTRAINT chk_asst_synth_offer_facts CHECK (
        observed_at > 0
        AND ((state = 'delisted' AND price_minor IS NULL AND price_scale IS NULL AND currency IS NULL
                AND merchant_name IS NULL AND merchant_url IS NULL AND expires_at IS NULL)
            OR (state IN ('available', 'out_of_stock') AND price_minor IS NOT NULL
                AND price_minor BETWEEN 0 AND 99999999999
                AND price_scale IS NOT NULL AND price_scale BETWEEN 0 AND 3
                AND currency IS NOT NULL AND CHAR_LENGTH(currency) = 3
                AND merchant_name IS NOT NULL AND CHAR_LENGTH(merchant_name) > 0
                AND merchant_url IS NOT NULL AND CHAR_LENGTH(merchant_url) > 0
                AND expires_at IS NOT NULL AND expires_at > observed_at))
    )
);

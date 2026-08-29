-- Eligibility module owns this schema (brief section 9, section 17).
--
-- Two separate projections so that suspending one account does not require
-- rewriting every video row. Milestone 2 populates only the fields available so
-- far (processing/durability/asset-lifecycle/publication); moderation joins in
-- Milestone 3, and per-source versioning hardens in Milestone 5.
CREATE SCHEMA IF NOT EXISTS eligibility;

CREATE TABLE eligibility.video_eligibility (
    video_id VARCHAR(36) PRIMARY KEY,
    creator_id VARCHAR(36) NOT NULL,
    processing_state VARCHAR(30) NOT NULL,
    processing_version INTEGER,
    durability_state VARCHAR(30) NOT NULL,
    publication_state VARCHAR(30) NOT NULL,
    publication_intent_requested BOOLEAN NOT NULL,
    asset_lifecycle_state VARCHAR(30) NOT NULL,
    legal_serving_state VARCHAR(30) NOT NULL,
    is_video_eligible BOOLEAN NOT NULL,
    source_version BIGINT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX video_eligibility_creator_idx ON eligibility.video_eligibility (creator_id);

CREATE TABLE eligibility.account_eligibility (
    account_id VARCHAR(36) PRIMARY KEY,
    account_state VARCHAR(30) NOT NULL,
    is_account_eligible BOOLEAN NOT NULL,
    source_version BIGINT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

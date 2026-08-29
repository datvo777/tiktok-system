-- Durable revocation records (brief section 16). Redis accelerates reads of this
-- table; it is never the sole source of permission, and there is no safety TTL.
CREATE TABLE platform.revocation (
    subject_type VARCHAR(30) NOT NULL,
    subject_id VARCHAR(100) NOT NULL,
    source_type VARCHAR(50) NOT NULL,
    source_version BIGINT NOT NULL,
    active BOOLEAN NOT NULL,
    reason VARCHAR(100),
    blocking_version BIGINT,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    cleared_at TIMESTAMPTZ,
    PRIMARY KEY (subject_type, subject_id, source_type)
);

CREATE INDEX revocation_active_idx
    ON platform.revocation (subject_type, subject_id)
    WHERE active;

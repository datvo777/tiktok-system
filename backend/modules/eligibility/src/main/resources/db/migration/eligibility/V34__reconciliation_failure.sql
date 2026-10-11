-- Consecutive-failure streaks for the reconciliation sweep, so a restart no longer
-- resets "permanently stuck" detection. Separate from account/video eligibility
-- rows: an id that keeps failing may have no eligibility row at all.
CREATE TABLE eligibility.reconciliation_failure (
    kind VARCHAR(16) NOT NULL,
    subject_id VARCHAR(64) NOT NULL,
    streak INT NOT NULL,
    last_error VARCHAR(500),
    updated_at TIMESTAMP NOT NULL,
    PRIMARY KEY (kind, subject_id)
);

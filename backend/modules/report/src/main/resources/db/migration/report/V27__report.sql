-- Report module owns this schema (brief section 9).
--
-- Moderation was entirely admin-initiated: a queue of pending uploads plus
-- quarantine and removal tools, with no way for the people who actually see a
-- problem to say so. This is that missing channel.
CREATE SCHEMA IF NOT EXISTS report;

CREATE TABLE report.report (
    report_id UUID PRIMARY KEY,
    -- What is being reported. Both persisted as strings, never ordinals
    -- (brief section 7).
    subject_type VARCHAR(20) NOT NULL,
    subject_id UUID NOT NULL,
    reporter_id UUID NOT NULL,
    reason VARCHAR(40) NOT NULL,
    detail VARCHAR(1000),
    state VARCHAR(30) NOT NULL,
    -- Who closed it and why; null while the report is still open.
    resolved_by UUID,
    resolution VARCHAR(30),
    resolution_note VARCHAR(1000),
    resolved_at TIMESTAMPTZ,
    aggregate_version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

-- One open report per person per subject. A viewer hitting "report" twice, or
-- tapping through a slow network, should not create a second row for a
-- moderator to read -- and without this the queue is trivially floodable by one
-- account. Partial, so the same person can report the same video again after an
-- earlier report was closed.
CREATE UNIQUE INDEX report_one_open_per_reporter_idx
    ON report.report (subject_type, subject_id, reporter_id)
    WHERE state = 'OPEN';

-- The moderator queue reads open reports oldest-first.
CREATE INDEX report_queue_idx ON report.report (state, created_at);

-- "How many people reported this?" is the signal that decides what a moderator
-- looks at first, so it needs its own index rather than a scan of the queue.
CREATE INDEX report_subject_idx ON report.report (subject_type, subject_id, state);

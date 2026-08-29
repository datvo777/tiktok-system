-- Notification module owns this schema (brief section 9, Milestone 7).
CREATE SCHEMA IF NOT EXISTS notification;

CREATE TABLE notification.notification (
    notification_id UUID PRIMARY KEY,
    recipient_account_id UUID NOT NULL,
    type VARCHAR(50) NOT NULL,
    message VARCHAR(500) NOT NULL,
    related_video_id UUID,
    read BOOLEAN NOT NULL DEFAULT false,
    aggregate_version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX notification_recipient_idx ON notification.notification (recipient_account_id, created_at DESC);

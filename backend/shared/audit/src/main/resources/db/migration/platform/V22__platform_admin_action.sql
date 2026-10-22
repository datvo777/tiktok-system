-- Who did what, to which thing, and why.
--
-- The outbox already records *what happened* and is never purged, so it is a
-- complete history of state changes. What it cannot answer is *who caused
-- them*: EventEnvelope carries producer, producerModule and correlationId, but
-- no acting account. Every admin decision in this system was anonymous before
-- this table existed.
--
-- Cross-cutting infrastructure written by several modules through one shared
-- component, exactly like platform.outbox_event and platform.revocation. It is
-- not a shared domain table: no module reads another module's rows to make a
-- decision, and nothing here feeds authorization.
CREATE TABLE platform.admin_action (
    action_id        UUID PRIMARY KEY,
    actor_account_id UUID NOT NULL,
    action           VARCHAR(60) NOT NULL,
    target_type      VARCHAR(30) NOT NULL,
    target_id        VARCHAR(100) NOT NULL,
    policy_category  VARCHAR(40),
    reason           VARCHAR(500),
    correlation_id   VARCHAR(100),
    occurred_at      TIMESTAMPTZ NOT NULL
);

-- "Everything ever done to this video/account", newest first — the query the
-- console makes when a moderator opens an entity.
CREATE INDEX admin_action_target_idx
    ON platform.admin_action (target_type, target_id, occurred_at DESC);

-- "Everything this reviewer did" — reviewer quality review, and the first thing
-- anyone asks after a bad decision.
CREATE INDEX admin_action_actor_idx
    ON platform.admin_action (actor_account_id, occurred_at DESC);

CREATE INDEX admin_action_occurred_idx
    ON platform.admin_action (occurred_at DESC);

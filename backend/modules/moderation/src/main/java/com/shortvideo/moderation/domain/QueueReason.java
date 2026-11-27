package com.shortvideo.moderation.domain;

/** Why a record entered the human review queue. */
public enum QueueReason {

    /** Every upload is queued ({@code queue-mode=ALL}, or the auto-approve switch is off). */
    UPLOAD,

    /** The screener saw something worth a person's time: flagged text, links, or a prior rejection. */
    RISK,

    /** The screener could not decide: no metadata yet, or the screener itself failed. */
    UNSCREENED,

    /** The owner asked to publish a video that was being held. */
    PUBLISH_REQUESTED
}

package com.shortvideo.moderation.domain;

/** Which uploads reach the human review queue straight away. */
public enum QueueMode {

    /** Every upload the screener does not approve is queued. The behaviour before intent gating. */
    ALL,

    /**
     * Only risky or unscreened uploads are queued on arrival. Others are held until the owner asks
     * to publish them.
     */
    INTENT_GATED
}

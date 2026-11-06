package com.shortvideo.moderation.domain;

/**
 * What the automated pre-screen concluded about a video.
 *
 * <p>Note what is missing: there is no "auto-reject". A screener that can take
 * content down on its own is a screener that can be wrong on its own, and the
 * signals available here — text and reputation — are nowhere near strong enough
 * to justify that. The worst outcome this can produce is a human having to look
 * at something, which is the status quo it is trying to improve on.
 *
 * <p>Uncertainty is not the same as risk. {@link #REFER_TO_HUMAN} means a signal pointed at the
 * content; {@link #HOLD_UNTIL_PUBLISH} means only that nothing vouches for the creator yet.
 */
public enum ScreeningVerdict {

    /** Publish without waiting for a person. */
    AUTO_APPROVE,

    /** Something is worth a person's time: queue it for review now. */
    REFER_TO_HUMAN,

    /**
     * Nothing looks wrong, but this creator is not trusted enough to skip review. Wait until the
     * owner asks to publish it before spending a person's time on it (or queue it now, when the
     * queue mode is {@code ALL}).
     */
    HOLD_UNTIL_PUBLISH
}

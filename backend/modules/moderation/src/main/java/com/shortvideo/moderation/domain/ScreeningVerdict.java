package com.shortvideo.moderation.domain;

/**
 * What the automated pre-screen concluded about a video.
 *
 * <p>Note what is missing: there is no "auto-reject". A screener that can take
 * content down on its own is a screener that can be wrong on its own, and the
 * signals available here — text and reputation — are nowhere near strong enough
 * to justify that. The worst outcome this can produce is a human having to look
 * at something, which is the status quo it is trying to improve on.
 */
public enum ScreeningVerdict {

    /** Publish without waiting for a person. */
    AUTO_APPROVE,

    /** Leave in the human queue. The default whenever anything is uncertain. */
    REFER_TO_HUMAN
}

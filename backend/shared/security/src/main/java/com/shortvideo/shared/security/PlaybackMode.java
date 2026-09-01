package com.shortvideo.shared.security;

/** Brief section 8. */
public final class PlaybackMode {

    public static final String OWNER_PREVIEW = "OWNER_PREVIEW";
    public static final String PUBLIC = "PUBLIC";
    /** Admin-only: lets a moderator watch content pending a decision, independent of ownership or eligibility. */
    public static final String MODERATOR_PREVIEW = "MODERATOR_PREVIEW";

    private PlaybackMode() {}
}

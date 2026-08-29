package com.shortvideo.shared.events;

/** Kafka topic names (brief section 11). */
public final class Topics {

    public static final String VIDEO_EVENTS = "video.events.v1";
    public static final String ACCOUNT_EVENTS = "account.events.v1";
    public static final String SOCIAL_EVENTS = "social.events.v1";

    /** Transcode commands: Video module -> media worker. */
    public static final String MEDIA_JOBS = "media.jobs.v1";

    /** Transcode results: media worker -> Video module. Not authoritative. */
    public static final String MEDIA_RESULTS = "media.results.v1";

    /** Milestone 7. */
    public static final String NOTIFICATION_EVENTS = "notification.events.v1";

    private Topics() {}
}

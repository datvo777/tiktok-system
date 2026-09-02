package com.shortvideo.shared.events;

/** Event type discriminators. */
public final class EventTypes {

    public static final String ACCOUNT_REGISTERED = "account.registered";
    public static final String ACCOUNT_SUSPENDED = "account.suspended";
    public static final String ACCOUNT_REINSTATED = "account.reinstated";

    // Milestone 2+
    public static final String VIDEO_UPLOAD_COMPLETED = "video.upload.completed";
    public static final String VIDEO_PROCESSING_READY = "video.processing.ready";
    public static final String VIDEO_PROCESSING_FAILED = "video.processing.failed";

    /** Title/description set at draft creation (brief section 7.1); these never change afterward. */
    public static final String VIDEO_METADATA_SET = "video.metadata.set";

    /**
     * Routed to {@link Topics#MEDIA_JOBS} instead of {@link Topics#VIDEO_EVENTS} —
     * see {@code TopicResolver}. Still an ordinary outbox event: the Video module
     * owns the aggregate and the version bump: only the topic differs.
     */
    public static final String MEDIA_JOB_DISPATCHED = "media.job.dispatched";

    /** Published directly by the media worker (no outbox — Rule 16), not consumed by TopicResolver. */
    public static final String MEDIA_RESULT_REPORTED = "media.result.reported";

    // Milestone 3+
    public static final String VIDEO_MODERATION_APPROVED = "video.moderation.approved";
    public static final String VIDEO_MODERATION_REJECTED = "video.moderation.rejected";
    public static final String VIDEO_MODERATION_REINSTATED = "video.moderation.reinstated";

    public static final String VIDEO_PUBLICATION_PENDING = "video.publication.pending";
    public static final String VIDEO_PUBLICATION_PUBLISHED = "video.publication.published";
    public static final String VIDEO_PUBLICATION_SUSPENDED = "video.publication.suspended";
    public static final String VIDEO_PUBLICATION_PRIVATE = "video.publication.private";
    public static final String VIDEO_PUBLICATION_REMOVED = "video.publication.removed";

    // Milestone 6+
    public static final String VIDEO_APPEAL_SUBMITTED = "video.appeal.submitted";
    public static final String VIDEO_APPEAL_APPROVED = "video.appeal.approved";
    public static final String VIDEO_APPEAL_DENIED = "video.appeal.denied";

    /**
     * assetLifecycleState changed independently of a processing outcome (retain
     * on rejection, restore, quarantine, remove). Deliberately distinct from
     * {@link #VIDEO_PROCESSING_READY}: that event means "a transcode job just
     * completed" to its consumers (e.g. the Publication coordinator treats it as
     * proof processing is ready), which is not true here.
     */
    public static final String VIDEO_LIFECYCLE_CHANGED = "video.lifecycle.changed";

    // Milestone 7+
    public static final String SOCIAL_VIDEO_COMMENTED = "social.video.commented";
    public static final String SOCIAL_CREATOR_FOLLOWED = "social.creator.followed";
    public static final String NOTIFICATION_CREATED = "notification.created";

    private EventTypes() {}
}

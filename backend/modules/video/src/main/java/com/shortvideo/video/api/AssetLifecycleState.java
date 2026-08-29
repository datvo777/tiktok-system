package com.shortvideo.video.api;

/** Brief section 7. Starts ACTIVE; the lifecycle workflow (Milestone 6) owns later states. */
public enum AssetLifecycleState {
    ACTIVE,
    REJECTED_RETAINED,
    DELETE_SCHEDULED,
    DELETION_IN_PROGRESS,
    QUARANTINED,
    DELETED,
    RESTORING
}

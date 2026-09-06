package com.shortvideo.shared.audit;

/** Stable action names. Persisted, so treat these as an API and never rename in place. */
public final class AuditActions {

    public static final String VIDEO_APPROVED = "video.approved";
    public static final String VIDEO_REJECTED = "video.rejected";
    public static final String VIDEO_QUARANTINED = "video.quarantined";
    public static final String VIDEO_RESTORED = "video.restored";
    public static final String VIDEO_REMOVED = "video.removed";
    public static final String VIDEO_REPROCESSED = "video.reprocessed";

    public static final String APPEAL_APPROVED = "appeal.approved";
    public static final String APPEAL_DENIED = "appeal.denied";

    public static final String ACCOUNT_SUSPENDED = "account.suspended";
    public static final String ACCOUNT_REINSTATED = "account.reinstated";

    private AuditActions() {}
}

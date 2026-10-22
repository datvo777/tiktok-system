package com.shortvideo.shared.audit;

/** Stable action names. Persisted, so treat these as an API and never rename in place. */
public final class AuditActions {

    public static final String VIDEO_APPROVED = "video.approved";
    public static final String VIDEO_REJECTED = "video.rejected";
    public static final String VIDEO_QUARANTINED = "video.quarantined";
    public static final String VIDEO_RESTORED = "video.restored";
    public static final String VIDEO_REMOVED = "video.removed";

    /**
     * A creator deleting their own video. Distinct from {@link #VIDEO_REMOVED},
     * which is a moderation takedown: the two have identical consequences for
     * the assets but very different meanings in an audit trail, and collapsing
     * them would make every creator deletion look like an enforcement action.
     */
    public static final String VIDEO_DELETED_BY_OWNER = "video.deleted_by_owner";
    public static final String VIDEO_REPROCESSED = "video.reprocessed";

    public static final String APPEAL_APPROVED = "appeal.approved";
    public static final String APPEAL_DENIED = "appeal.denied";

    public static final String ACCOUNT_SUSPENDED = "account.suspended";
    public static final String ACCOUNT_REINSTATED = "account.reinstated";

    /** The account's own act, so actor and target are the same account. */
    public static final String MFA_ENROLLED = "account.mfa_enrolled";
    /** A recovery code was spent to sign in: worth a look, whether it was the owner or not. */
    public static final String MFA_RECOVERY_USED = "account.mfa_recovery_used";

    private AuditActions() {}
}

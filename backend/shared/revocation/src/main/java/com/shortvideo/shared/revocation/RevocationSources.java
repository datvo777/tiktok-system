package com.shortvideo.shared.revocation;

import java.util.Set;

/**
 * The restrictive sources a revocation can come from, one name per module that writes one. Shared so the
 * module that writes a source and the media path that decides whether to look past it cannot drift apart.
 */
public final class RevocationSources {

    /** A moderation rejection of a video. */
    public static final String MODERATION = "MODERATION";
    /** The suspension of an account. */
    public static final String ACCOUNT_SUSPENSION = "ACCOUNT";
    /** An admin lifecycle hold or removal of a video (quarantine, takedown). */
    public static final String LIFECYCLE = "LIFECYCLE";

    private RevocationSources() {}

    /**
     * What a staff preview looks past: the decisions staff are there to review or investigate. A rejected
     * video has to be watchable to decide its appeal, and a suspended account's videos to investigate it.
     * Everything else (a lifecycle hold, a source added later) still blocks staff, as it blocks everyone.
     */
    public static Set<String> ignoredByStaffPreview(String subjectType) {
        if (RevocationSubjects.VIDEO.equals(subjectType)) {
            return Set.of(MODERATION);
        }
        if (RevocationSubjects.ACCOUNT.equals(subjectType)) {
            return Set.of(ACCOUNT_SUSPENSION);
        }
        return Set.of();
    }
}

package com.shortvideo.shared.audit;

/** What an admin action was performed against. Persisted — see {@link AuditActions}. */
public final class AuditTargets {

    public static final String VIDEO = "VIDEO";
    public static final String ACCOUNT = "ACCOUNT";

    private AuditTargets() {}
}

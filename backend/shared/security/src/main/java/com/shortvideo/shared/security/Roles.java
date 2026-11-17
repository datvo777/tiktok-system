package com.shortvideo.shared.security;

import java.util.Set;

/**
 * The staff roles and the {@code @PreAuthorize} expressions built from them, so a controller names
 * what it needs ({@link #CAN_MODERATE}, {@link #IS_ADMIN}) instead of repeating role strings.
 *
 * <p>MODERATOR works the review queue and the report queue: reversible, per-video decisions. ADMIN
 * does that too, and alone holds what affects a person's whole account, cannot be undone, decides
 * an appeal against a moderator, or reads the audit trail and the operational endpoints. ADMIN is
 * listed beside MODERATOR everywhere rather than inherited through a hierarchy, so what a route
 * accepts can be read where it is declared.
 */
public final class Roles {

    public static final String USER = "USER";
    public static final String MODERATOR = "MODERATOR";
    public static final String ADMIN = "ADMIN";

    /** Roles that may work the moderation queues. */
    public static final Set<String> MODERATING = Set.of(MODERATOR, ADMIN);

    public static final String CAN_MODERATE = "hasAnyRole('MODERATOR','ADMIN')";
    public static final String IS_ADMIN = "hasRole('ADMIN')";

    private Roles() {}
}

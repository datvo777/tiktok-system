package com.shortvideo.shared.security;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/** The one definition of "this token predates the account's last password change". */
final class CredentialFreshness {

    /**
     * A JWT's iat is whole seconds while password_changed_at is not, so comparing them raw rejects
     * a token issued in the same second as the change it came after (e.g. logging in right after
     * registering). Compare at the token's own precision; the price is that a token minted earlier
     * in that same second is not revoked.
     */
    static boolean isStale(Instant issuedAt, Instant passwordChangedAt) {
        return issuedAt.isBefore(passwordChangedAt.truncatedTo(ChronoUnit.SECONDS));
    }

    private CredentialFreshness() {}
}

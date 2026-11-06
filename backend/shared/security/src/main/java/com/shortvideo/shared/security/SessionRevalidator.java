package com.shortvideo.shared.security;

import com.shortvideo.shared.revocation.RevocationCache;
import com.shortvideo.shared.revocation.RevocationSubjects;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Whether a long-lived connection (the realtime stream) should be dropped because the account
 * behind it has lost its entitlement since it was opened.
 *
 * <p>This is deliberately weaker than {@link JwtAuthenticationFilter}: it reads only the Redis
 * caches, never PostgreSQL, and when Redis cannot answer it keeps the connection. The filter fails
 * closed because it guards data; this guards a stream that carries only "something changed, ask
 * the API" hints, and the API it points at still runs the full filter on every request. So the
 * worst a stale answer costs is a hint delivered a minute late to an account that can no longer
 * act on it.
 *
 * <p>The connection's own token is not checked against the logout deny-list: {@code /auth/refresh}
 * revokes the token it replaces, so a stream opened with the previous token would otherwise be cut
 * ten minutes in, on every refresh.
 */
@Component
public class SessionRevalidator {

    private static final Logger log = LoggerFactory.getLogger(SessionRevalidator.class);

    private final RevocationCache revocationCache;
    private final CredentialFreshnessCache credentialFreshnessCache;

    public SessionRevalidator(RevocationCache revocationCache, CredentialFreshnessCache credentialFreshnessCache) {
        this.revocationCache = revocationCache;
        this.credentialFreshnessCache = credentialFreshnessCache;
    }

    /**
     * @param issuedAt the {@code iat} of the token the connection was opened with
     * @return false when the account is suspended, or changed its password after that token was
     *     issued; true otherwise, including when the answer is unknown
     */
    public boolean isStillEntitled(String accountId, Instant issuedAt) {
        try {
            if (revocationCache.isDenied(RevocationSubjects.ACCOUNT, accountId)) {
                return false;
            }
            Optional<Instant> changedAt = credentialFreshnessCache.get(accountId);
            return changedAt.isEmpty() || !CredentialFreshness.isStale(issuedAt, changedAt.get());
        } catch (RuntimeException e) {
            log.debug("Could not revalidate account {}; keeping its stream", accountId, e);
            return true;
        }
    }
}

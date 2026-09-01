package com.shortvideo.shared.security;

import java.time.Instant;
import java.util.Set;

/**
 * The verified caller identity carried in the SecurityContext.
 *
 * @param tokenId the token's {@code jti}, so a specific token can be revoked at
 *     logout without waiting out its TTL (see {@code SessionTokenDenyList}).
 */
public record AuthenticatedAccount(
        String accountId, Set<String> roles, String tokenId, Instant expiresAt) {}

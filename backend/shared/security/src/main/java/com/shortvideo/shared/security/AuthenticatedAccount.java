package com.shortvideo.shared.security;

import java.time.Instant;
import java.util.Set;

/**
 * The verified caller identity carried in the SecurityContext.
 *
 * @param tokenId the token's {@code jti}, so a specific token can be revoked at
 *     logout without waiting out its TTL (see {@code SessionTokenDenyList}).
 * @param issuedAt the token's {@code iat}, compared against the account's
 *     password-change timestamp so a token minted before a password change is
 *     rejected instead of trusted for the rest of its TTL (see {@code
 *     CredentialFreshnessReader}).
 */
public record AuthenticatedAccount(
        String accountId, Set<String> roles, String tokenId, Instant issuedAt, Instant expiresAt) {}

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
 * @param authTime when the person last proved who they are (login). {@code /auth/refresh} carries it
 *     over unchanged, so renewing a session never moves it; the session's absolute lifetime is counted
 *     from here. Named after the OpenID Connect {@code auth_time} claim it is stored in.
 * @param amr how the person proved who they are (RFC 8176): {@code pwd} for the password, {@code otp} once a
 *     one-time code or recovery code has been accepted as well. {@code /auth/refresh} carries it over
 *     unchanged and never adds to it; a token with none counts as password-only.
 */
public record AuthenticatedAccount(
        String accountId,
        Set<String> roles,
        String tokenId,
        Instant issuedAt,
        Instant expiresAt,
        Instant authTime,
        Set<String> amr) {

    public static final String PASSWORD = "pwd";
    public static final String ONE_TIME_CODE = "otp";

    public AuthenticatedAccount(
            String accountId, Set<String> roles, String tokenId, Instant issuedAt, Instant expiresAt, Instant authTime) {
        this(accountId, roles, tokenId, issuedAt, expiresAt, authTime, Set.of(PASSWORD));
    }

    /** Whether a second factor was part of this sign-in. */
    public boolean hasSecondFactor() {
        return amr.contains(ONE_TIME_CODE);
    }

    /** A token with no recorded sign-in time counts from when it was issued; see {@code JwtService#parse}. */
    public AuthenticatedAccount(String accountId, Set<String> roles, String tokenId, Instant issuedAt, Instant expiresAt) {
        this(accountId, roles, tokenId, issuedAt, expiresAt, issuedAt);
    }
}

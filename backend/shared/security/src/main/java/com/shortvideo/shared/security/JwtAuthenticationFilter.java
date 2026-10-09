package com.shortvideo.shared.security;

import com.shortvideo.shared.revocation.DurableRevocationReader;
import com.shortvideo.shared.revocation.RevocationCache;
import com.shortvideo.shared.revocation.RevocationSubjects;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Accepts either transport on /api and /internal; requires the cookie on /media.
 *
 * <p>Rule 17: media requests carry cookies, not headers. Any authorization
 * mechanism that requires a request header cannot apply to /media, so a bearer
 * token is deliberately ignored there — otherwise a test passing a header would
 * "prove" a path the browser can never exercise.
 *
 * <p>A valid signature is necessary but not sufficient. Signature and expiry are
 * facts about the token; whether the bearer is still entitled to act is a fact
 * about the account, and it can change during the token's lifetime. Two checks
 * close that window:
 *
 * <ul>
 *   <li>the token's {@code jti} must not be on the logout deny-list, so signing
 *       out ends the session for the bearer token as well as the cookie;
 *   <li>the account must not be revoked, so an admin suspension takes effect on
 *       the next request instead of after up to a full token TTL. This mirrors
 *       the authority order the media gateway already applies — Redis deny fast
 *       path, then the durable PostgreSQL record;
 *   <li>the token must have been issued at or after the account's last password
 *       change, so changing a password actually ends every session minted with
 *       the old credential instead of leaving them valid for the rest of their
 *       TTL;
 *   <li>the caller's roles are re-derived from the account (cached, write-through)
 *       rather than trusted from the token claim, so a promotion or demotion
 *       applies on the next request instead of after a re-login.
 * </ul>
 *
 * <p>Rule 9 applies to the durable check: if PostgreSQL cannot answer, the request
 * is left unauthenticated rather than trusted, because "unknown" is not "allowed".
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);
    private static final String BEARER_PREFIX = "Bearer ";
    public static final String MEDIA_PATH_PREFIX = "/media";
    /**
     * Request attribute set when a token was refused only because its session reached the absolute
     * limit, so the 401 can say so ("sign in again") instead of looking like any other bad credential.
     */
    public static final String SESSION_LIFETIME_EXCEEDED = "sv.session.lifetimeExceeded";

    private final JwtService jwtService;
    private final String sessionCookieName;
    private final SessionTokenDenyList denyList;
    private final RevocationCache revocationCache;
    private final DurableRevocationReader revocationReader;
    private final CredentialFreshnessCache credentialFreshnessCache;
    private final CredentialFreshnessReader credentialFreshnessReader;
    private final RoleFreshnessCache roleFreshnessCache;
    private final RoleFreshnessReader roleFreshnessReader;

    public JwtAuthenticationFilter(
            JwtService jwtService,
            SessionCookies sessionCookies,
            SessionTokenDenyList denyList,
            RevocationCache revocationCache,
            DurableRevocationReader revocationReader,
            CredentialFreshnessCache credentialFreshnessCache,
            CredentialFreshnessReader credentialFreshnessReader,
            RoleFreshnessCache roleFreshnessCache,
            RoleFreshnessReader roleFreshnessReader) {
        this.jwtService = jwtService;
        this.sessionCookieName = sessionCookies.sessionCookieName();
        this.denyList = denyList;
        this.revocationCache = revocationCache;
        this.revocationReader = revocationReader;
        this.credentialFreshnessCache = credentialFreshnessCache;
        this.credentialFreshnessReader = credentialFreshnessReader;
        this.roleFreshnessCache = roleFreshnessCache;
        this.roleFreshnessReader = roleFreshnessReader;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            String token = resolveToken(request);
            if (token != null) {
                try {
                    AuthenticatedAccount parsed = jwtService.parse(token);
                    AuthenticatedAccount account = stillEntitled(parsed, request);
                    if (account != null) {
                        var authorities = account.roles().stream()
                                .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                                .map(a -> (org.springframework.security.core.GrantedAuthority) a)
                                .toList();
                        var authentication =
                                new UsernamePasswordAuthenticationToken(account, null, authorities);
                        SecurityContextHolder.getContext().setAuthentication(authentication);
                    }
                } catch (InvalidTokenException e) {
                    // Leave the context unauthenticated; the entry point returns 401.
                    log.debug("Rejected token on {}: {}", request.getRequestURI(), e.getMessage());
                    SecurityContextHolder.clearContext();
                }
            }
        }
        chain.doFilter(request, response);
    }

    /**
     * @return the caller with roles re-derived from the account, or null to leave the
     *     request unauthenticated — the configured entry point then answers 401, which
     *     is the correct signal for "this credential is no longer good" as opposed to
     *     "you may not do this".
     */
    private AuthenticatedAccount stillEntitled(AuthenticatedAccount account, HttpServletRequest request) {
        if (denyList.isRevoked(account.tokenId())) {
            log.debug("Rejected signed-out token on {}", request.getRequestURI());
            return null;
        }
        try {
            if (revocationCache.isDenied(RevocationSubjects.ACCOUNT, account.accountId())
                    || revocationReader.isActive(RevocationSubjects.ACCOUNT, account.accountId())) {
                log.debug("Rejected token for revoked account on {}", request.getRequestURI());
                return null;
            }
        } catch (DataAccessException e) {
            // Rule 9: unknown state denies. Failing open here would mean a database
            // blip silently reinstates every suspended account.
            log.warn("Revocation state unavailable; refusing to authenticate", e);
            return null;
        }
        try {
            if (isStaleAgainstPasswordChange(account, request)) {
                return null;
            }
        } catch (DataAccessException e) {
            log.warn("Credential-freshness state unavailable; refusing to authenticate", e);
            return null;
        }
        try {
            Set<String> roles = currentRoles(account.accountId());
            if (roles == null) {
                log.debug("Rejected token for missing account on {}", request.getRequestURI());
                return null;
            }
            // Judged on the roles the account holds now, so a promotion to admin applies the shorter limit at once.
            if (jwtService.exceedsAbsoluteLifetime(account, roles, Instant.now())) {
                log.debug("Rejected token past the session's absolute lifetime on {}", request.getRequestURI());
                request.setAttribute(SESSION_LIFETIME_EXCEEDED, Boolean.TRUE);
                return null;
            }
            // The token's own roles are only what was true at issue time.
            return new AuthenticatedAccount(
                    account.accountId(), roles, account.tokenId(), account.issuedAt(), account.expiresAt(), account.authTime());
        } catch (DataAccessException e) {
            log.warn("Role state unavailable; refusing to authenticate", e);
            return null;
        }
    }

    /** @return the account's current roles, or null if the account no longer exists. */
    private Set<String> currentRoles(String accountId) {
        Optional<Set<String>> cached = roleFreshnessCache.get(accountId);
        if (cached.isPresent()) {
            return cached.get();
        }
        Optional<Set<String>> durable = roleFreshnessReader.rolesOf(accountId);
        durable.ifPresent(roles -> roleFreshnessCache.put(accountId, roles));
        return durable.orElse(null);
    }

    /** @return true when the token predates the account's last password change. */
    private boolean isStaleAgainstPasswordChange(AuthenticatedAccount account, HttpServletRequest request) {
        Optional<Instant> cached = credentialFreshnessCache.get(account.accountId());
        Instant changedAt;
        if (cached.isPresent()) {
            changedAt = cached.get();
        } else {
            // Cache miss (expired TTL, or Redis unreachable): the durable row is
            // always consulted — a miss here is never itself a reason to allow.
            changedAt = credentialFreshnessReader
                    .passwordChangedAt(account.accountId())
                    .orElse(Instant.EPOCH);
            credentialFreshnessCache.put(account.accountId(), changedAt);
        }
        // A JWT's iat is whole seconds while password_changed_at is not, so comparing them
        // raw rejects a token issued in the same second as the change it came after (e.g.
        // logging in right after registering). Compare at the token's own precision; the
        // price is that a token minted earlier in that same second is not revoked.
        if (CredentialFreshness.isStale(account.issuedAt(), changedAt)) {
            log.debug("Rejected token issued before the last password change on {}", request.getRequestURI());
            return true;
        }
        return false;
    }

    private String resolveToken(HttpServletRequest request) {
        String path = request.getRequestURI();
        boolean mediaRequest = path != null && path.startsWith(MEDIA_PATH_PREFIX);

        if (!mediaRequest) {
            String header = request.getHeader("Authorization");
            if (header != null && header.startsWith(BEARER_PREFIX)) {
                String value = header.substring(BEARER_PREFIX.length()).trim();
                if (!value.isEmpty()) {
                    return value;
                }
            }
        }
        return cookieValue(request);
    }

    private String cookieValue(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        return List.of(cookies).stream()
                .filter(c -> sessionCookieName.equals(c.getName()))
                .map(Cookie::getValue)
                .filter(v -> v != null && !v.isBlank())
                .findFirst()
                .orElse(null);
    }
}

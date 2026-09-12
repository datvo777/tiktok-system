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
import java.util.List;
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
 *       path, then the durable PostgreSQL record.
 * </ul>
 *
 * <p>Rule 9 applies to the durable check: if PostgreSQL cannot answer, the request
 * is left unauthenticated rather than trusted, because "unknown" is not "allowed".
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);
    private static final String BEARER_PREFIX = "Bearer ";
    public static final String MEDIA_PATH_PREFIX = "/media";

    private final JwtService jwtService;
    private final String sessionCookieName;
    private final SessionTokenDenyList denyList;
    private final RevocationCache revocationCache;
    private final DurableRevocationReader revocationReader;

    public JwtAuthenticationFilter(
            JwtService jwtService,
            SessionCookies sessionCookies,
            SessionTokenDenyList denyList,
            RevocationCache revocationCache,
            DurableRevocationReader revocationReader) {
        this.jwtService = jwtService;
        this.sessionCookieName = sessionCookies.sessionCookieName();
        this.denyList = denyList;
        this.revocationCache = revocationCache;
        this.revocationReader = revocationReader;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            String token = resolveToken(request);
            if (token != null) {
                try {
                    AuthenticatedAccount account = jwtService.parse(token);
                    if (stillEntitled(account, request)) {
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
     * @return false to leave the request unauthenticated — the configured entry
     *     point then answers 401, which is the correct signal for "this credential
     *     is no longer good" as opposed to "you may not do this".
     */
    private boolean stillEntitled(AuthenticatedAccount account, HttpServletRequest request) {
        if (denyList.isRevoked(account.tokenId())) {
            log.debug("Rejected signed-out token on {}", request.getRequestURI());
            return false;
        }
        try {
            if (revocationCache.isDenied(RevocationSubjects.ACCOUNT, account.accountId())
                    || revocationReader.isActive(RevocationSubjects.ACCOUNT, account.accountId())) {
                log.debug("Rejected token for revoked account on {}", request.getRequestURI());
                return false;
            }
        } catch (DataAccessException e) {
            // Rule 9: unknown state denies. Failing open here would mean a database
            // blip silently reinstates every suspended account.
            log.warn("Revocation state unavailable; refusing to authenticate", e);
            return false;
        }
        return true;
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

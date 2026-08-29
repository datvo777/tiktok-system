package com.shortvideo.shared.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);
    private static final String BEARER_PREFIX = "Bearer ";
    public static final String MEDIA_PATH_PREFIX = "/media";

    private final JwtService jwtService;
    private final String sessionCookieName;

    public JwtAuthenticationFilter(JwtService jwtService, SessionCookies sessionCookies) {
        this.jwtService = jwtService;
        this.sessionCookieName = sessionCookies.sessionCookieName();
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
                    var authorities = account.roles().stream()
                            .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                            .map(a -> (org.springframework.security.core.GrantedAuthority) a)
                            .toList();
                    var authentication =
                            new UsernamePasswordAuthenticationToken(account, null, authorities);
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                } catch (InvalidTokenException e) {
                    // Leave the context unauthenticated; the entry point returns 401.
                    log.debug("Rejected token on {}: {}", request.getRequestURI(), e.getMessage());
                    SecurityContextHolder.clearContext();
                }
            }
        }
        chain.doFilter(request, response);
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

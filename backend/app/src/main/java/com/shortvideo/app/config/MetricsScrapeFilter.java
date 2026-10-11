package com.shortvideo.app.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Lets Prometheus read {@code /actuator/prometheus} with a shared token, instead of the whole
 * {@code /actuator/**} surface being closed to it.
 *
 * <p>The actuator is admin-only because the rest of it (health detail, env, metrics browsing)
 * names internal topology. A scraper has no session to present, so it gets exactly one route and
 * one role that nothing else accepts: the credential is checked here, for a GET of this one path,
 * and produces {@code ROLE_METRICS_SCRAPER}, which only the matcher for that path honours. The
 * request is stateless, so nothing carries over to any other request that presents the same token.
 */
final class MetricsScrapeFilter extends OncePerRequestFilter {

    static final String PATH = "/actuator/prometheus";
    static final String ROLE = "METRICS_SCRAPER";
    private static final String BEARER = "Bearer ";

    private final byte[] token;

    MetricsScrapeFilter(String token) {
        this.token = token.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"GET".equals(request.getMethod()) || !PATH.equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null
                && header.startsWith(BEARER)
                // Constant time, so the token cannot be recovered a byte at a time from response timing.
                && MessageDigest.isEqual(header.substring(BEARER.length()).getBytes(StandardCharsets.UTF_8), token)) {
            SecurityContextHolder.getContext()
                    .setAuthentication(new UsernamePasswordAuthenticationToken(
                            "prometheus", null, List.of(new SimpleGrantedAuthority("ROLE_" + ROLE))));
        }
        chain.doFilter(request, response);
    }
}

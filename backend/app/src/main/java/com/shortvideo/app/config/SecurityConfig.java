package com.shortvideo.app.config;

import com.shortvideo.shared.revocation.DurableRevocationReader;
import com.shortvideo.shared.revocation.RevocationCache;
import com.shortvideo.shared.security.JwtAuthenticationFilter;
import com.shortvideo.shared.security.JwtService;
import com.shortvideo.shared.security.SessionCookies;
import com.shortvideo.shared.security.SessionTokenDenyList;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        // BCrypt for the local MVP (brief section 12.1). Argon2id is the other
        // sanctioned choice; do not downgrade to an unsalted digest.
        return new BCryptPasswordEncoder(10);
    }

    @Bean
    public JwtAuthenticationFilter jwtAuthenticationFilter(
            JwtService jwtService,
            SessionCookies sessionCookies,
            SessionTokenDenyList denyList,
            RevocationCache revocationCache,
            DurableRevocationReader revocationReader) {
        return new JwtAuthenticationFilter(
                jwtService, sessionCookies, denyList, revocationCache, revocationReader);
    }

    /**
     * Whether the OpenAPI document and Swagger UI are reachable without
     * authentication.
     *
     * <p>Left open, those two paths enumerate the complete API surface —
     * including every {@code /internal/**} admin route — to anyone who can reach
     * the host. That is a fine trade locally and a poor one anywhere else, so the
     * default is closed and a deployment has to opt in:
     * {@code shortvideo.security.public-api-docs=true}.
     */
    @Bean
    public SecurityFilterChain filterChain(
            HttpSecurity http,
            JwtAuthenticationFilter jwtFilter,
            @Value("${shortvideo.security.public-api-docs:false}") boolean publicApiDocs)
            throws Exception {
        http
                // Stateless bearer/cookie auth. Cross-site POSTs cannot carry the
                // SameSite=Lax session cookie, which is what stands in for CSRF
                // tokens locally. Relaxing SameSite means adding CSRF tokens back.
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/api/v1/accounts").permitAll()
                        .requestMatchers("/api/v1/auth/login", "/api/v1/auth/logout").permitAll()
                        // Container probes only. The aggregate /actuator/health
                        // response carries datasource URLs, broker addresses and
                        // disk paths, so it is not public even though liveness and
                        // readiness are — and neither of those discloses detail.
                        .requestMatchers("/actuator/health/liveness", "/actuator/health/readiness").permitAll()
                        // Public only where a deployment has explicitly said so;
                        // otherwise admin-only, like the rest of the internal surface.
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html")
                        .access((authentication, context) -> new AuthorizationDecision(
                                publicApiDocs
                                        || (authentication.get() != null
                                                && authentication.get().getAuthorities().stream()
                                                        .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority())))))
                        // Everything else actuator exposes — the aggregate health
                        // report, prometheus, metrics, info — enumerates internal
                        // topology and is admin-only.
                        .requestMatchers("/actuator/**").hasRole("ADMIN")
                        .requestMatchers("/internal/**").hasRole("ADMIN")
                        // ---------------------------------------------------------------
                        // The anonymous read tier.
                        //
                        // Everything below is public *content*: what the feed would
                        // already show any signed-in stranger. Nothing here is
                        // viewer-specific, and nothing here writes.
                        //
                        // Reading is what makes a shared link, a search result or a
                        // creator page worth anything — with the whole API behind a
                        // session, every one of those terminated at a signup wall.
                        // Participating still requires an account: liking,
                        // commenting, following, saving, reporting and uploading are
                        // all POST/DELETE and none of them are listed here.
                        //
                        // GET-only, and enumerated one route at a time rather than by
                        // prefix, so that adding an endpoint under one of these paths
                        // is private by default and becomes public only deliberately.
                        // ---------------------------------------------------------------
                        .requestMatchers(HttpMethod.GET, "/api/v1/feed").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/feed/videos/*").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/search").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/creators/*").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/creators/*/videos").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/videos/*/poster").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/videos/*/counts").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/videos/*/comments").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/videos/*/comments/*/replies").permitAll()
                        // Minting a public playback session: the endpoint itself
                        // re-checks the full eligibility invariant and binds the
                        // token to a signed device cookie, so this is not a way to
                        // reach anything the feed would not already list.
                        .requestMatchers(HttpMethod.POST, "/api/v1/videos/*/public-playback-session").permitAll()

                        // Media was already authorized per request by the gateway
                        // (Rule 18) — this line was a second, coarser lock in front
                        // of it. Removing it lets a signed-out viewer play a public
                        // video; the gateway still requires a valid playback cookie
                        // AND a matching identity, which for a signed-out viewer is
                        // the signed device cookie. The filter continues to ignore
                        // bearer tokens on this path (Rule 17).
                        .requestMatchers("/media/**").permitAll()

                        // Everything not named above: private.
                        .anyRequest().authenticated())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, authException) ->
                                writeProblem(response, HttpServletResponse.SC_UNAUTHORIZED,
                                        "Unauthorized", "Valid credentials are required"))
                        .accessDeniedHandler((request, response, deniedException) ->
                                writeProblem(response, HttpServletResponse.SC_FORBIDDEN,
                                        "Forbidden", "You may not access this resource")))
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    private void writeProblem(HttpServletResponse response, int status, String title, String detail)
            throws java.io.IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write(
                "{\"type\":\"about:blank\",\"title\":\"" + title + "\",\"status\":" + status
                        + ",\"detail\":\"" + detail + "\"}");
    }
}

package com.shortvideo.app.config;

import com.shortvideo.shared.revocation.DurableRevocationReader;
import com.shortvideo.shared.revocation.RevocationCache;
import com.shortvideo.shared.security.JwtAuthenticationFilter;
import com.shortvideo.shared.security.JwtService;
import com.shortvideo.shared.security.SessionCookies;
import com.shortvideo.shared.security.SessionTokenDenyList;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
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

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, JwtAuthenticationFilter jwtFilter) throws Exception {
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
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        // Everything else actuator exposes — the aggregate health
                        // report, prometheus, metrics, info — enumerates internal
                        // topology and is admin-only.
                        .requestMatchers("/actuator/**").hasRole("ADMIN")
                        .requestMatchers("/internal/**").hasRole("ADMIN")
                        // Media is authenticated by the session cookie only; the
                        // filter ignores bearer tokens on this path (Rule 17).
                        .requestMatchers("/media/**").authenticated()
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

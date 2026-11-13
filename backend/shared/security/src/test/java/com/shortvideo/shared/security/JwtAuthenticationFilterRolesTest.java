package com.shortvideo.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.shortvideo.shared.revocation.DurableRevocationReader;
import com.shortvideo.shared.revocation.RevocationCache;
import jakarta.servlet.FilterChain;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** The filter must trust the account's current roles, never the claim baked into the token. */
class JwtAuthenticationFilterRolesTest {

    private static final String ACCOUNT_ID = "11111111-1111-1111-1111-111111111111";

    private JwtService jwtService;
    private RoleFreshnessCache roleCache;
    private RoleFreshnessReader roleReader;
    private JwtAuthenticationFilter filter;
    private String token;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        JwtProperties properties = new JwtProperties();
        properties.setSecret("a-local-test-secret-that-is-long-enough-32");
        properties.setIssuer("short-video-local");
        properties.setAudience("short-video-web");
        properties.setTtl(Duration.ofMinutes(30));
        jwtService = new JwtService(properties, new TokenKeys(properties));

        SessionCookies cookies = mock(SessionCookies.class);
        when(cookies.sessionCookieName()).thenReturn("session");
        SessionTokenDenyList denyList = mock(SessionTokenDenyList.class);
        RevocationCache revocationCache = mock(RevocationCache.class);
        DurableRevocationReader revocationReader = mock(DurableRevocationReader.class);
        CredentialFreshnessCache credentialCache = mock(CredentialFreshnessCache.class);
        CredentialFreshnessReader credentialReader = mock(CredentialFreshnessReader.class);
        when(credentialCache.get(anyString())).thenReturn(Optional.of(Instant.EPOCH));
        roleCache = mock(RoleFreshnessCache.class);
        roleReader = mock(RoleFreshnessReader.class);
        when(roleCache.get(anyString())).thenReturn(Optional.empty());

        filter = new JwtAuthenticationFilter(
                jwtService, cookies, denyList, revocationCache, revocationReader,
                credentialCache, credentialReader, roleCache, roleReader);
        // Token minted while the account was still an ADMIN.
        token = jwtService.issue(ACCOUNT_ID, Set.of("USER", "ADMIN")).token();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private Authentication run() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/me");
        request.addHeader("Authorization", "Bearer " + token);
        filter.doFilter(request, new MockHttpServletResponse(), mock(FilterChain.class));
        return SecurityContextHolder.getContext().getAuthentication();
    }

    @Test
    void demotedAdminLosesRoleAdminOnTheNextRequestEvenWithAValidToken() throws Exception {
        when(roleReader.rolesOf(ACCOUNT_ID)).thenReturn(Optional.of(Set.of("USER")));

        Authentication auth = run();

        assertThat(auth).isNotNull();
        assertThat(auth.getAuthorities()).extracting(Object::toString).containsExactly("ROLE_USER");
        assertThat(((AuthenticatedAccount) auth.getPrincipal()).roles()).containsExactly("USER");
        verify(roleCache).put(ACCOUNT_ID, Set.of("USER"));
    }

    @Test
    void cachedRolesAreUsedWithoutTouchingTheDatabase() throws Exception {
        when(roleCache.get(ACCOUNT_ID)).thenReturn(Optional.of(Set.of("USER", "MODERATOR")));

        Authentication auth = run();

        assertThat(auth.getAuthorities()).extracting(Object::toString)
                .containsExactlyInAnyOrder("ROLE_USER", "ROLE_MODERATOR");
        verify(roleReader, never()).rolesOf(any());
    }

    @Test
    void deletedAccountIsRejectedEvenWithAValidToken() throws Exception {
        when(roleReader.rolesOf(ACCOUNT_ID)).thenReturn(Optional.empty());

        assertThat(run()).isNull();
    }

    @Test
    void unknownRoleStateDeniesInsteadOfFallingBackToTheTokenClaim() throws Exception {
        when(roleReader.rolesOf(ACCOUNT_ID)).thenThrow(new QueryTimeoutException("db down"));

        assertThat(run()).isNull();
    }

    @Test
    void unreadableStateIsMarkedSoItCanBeAnswered503NotTaughtAsABadCredential() throws Exception {
        when(roleReader.rolesOf(ACCOUNT_ID)).thenThrow(new QueryTimeoutException("db down"));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/auth/me");
        request.addHeader("Authorization", "Bearer " + token);

        filter.doFilter(request, new MockHttpServletResponse(), mock(FilterChain.class));

        assertThat(request.getAttribute(JwtAuthenticationFilter.AUTH_STATE_UNAVAILABLE)).isEqualTo(Boolean.TRUE);
    }

    @Test
    void aDeletedAccountIsNotMarkedUnavailable() throws Exception {
        when(roleReader.rolesOf(ACCOUNT_ID)).thenReturn(Optional.empty());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/auth/me");
        request.addHeader("Authorization", "Bearer " + token);

        filter.doFilter(request, new MockHttpServletResponse(), mock(FilterChain.class));

        assertThat(request.getAttribute(JwtAuthenticationFilter.AUTH_STATE_UNAVAILABLE)).isNull();
    }
}

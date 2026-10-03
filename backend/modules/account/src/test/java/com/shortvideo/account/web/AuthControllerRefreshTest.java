package com.shortvideo.account.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.shortvideo.account.api.AccountState;
import com.shortvideo.account.api.AccountView;
import com.shortvideo.account.domain.AccountService;
import com.shortvideo.shared.security.AuthenticatedAccount;
import com.shortvideo.shared.security.ClientIpResolver;
import com.shortvideo.shared.security.JwtProperties;
import com.shortvideo.shared.security.JwtService;
import com.shortvideo.shared.security.LoginRateLimiter;
import com.shortvideo.shared.security.SessionCookies;
import com.shortvideo.shared.security.SessionTokenDenyList;
import com.shortvideo.shared.security.TokenKeys;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class AuthControllerRefreshTest {

    private static final String ACCOUNT_ID = "22222222-2222-2222-2222-222222222222";

    private AccountService accountService;
    private SessionTokenDenyList denyList;
    private JwtService jwtService;
    private AuthController controller;
    private AuthenticatedAccount caller;

    @BeforeEach
    void setUp() {
        JwtProperties properties = new JwtProperties();
        properties.setSecret("a-local-test-secret-that-is-long-enough-32");
        properties.setIssuer("short-video-local");
        properties.setAudience("short-video-web");
        properties.setTtl(Duration.ofMinutes(30));
        jwtService = new JwtService(properties, new TokenKeys(properties));

        accountService = mock(AccountService.class);
        denyList = mock(SessionTokenDenyList.class);
        controller = new AuthController(
                accountService,
                jwtService,
                new SessionCookies(properties),
                denyList,
                mock(LoginRateLimiter.class),
                mock(ClientIpResolver.class));

        when(accountService.find(ACCOUNT_ID))
                .thenReturn(Optional.of(new AccountView(
                        ACCOUNT_ID, "Name", "handle", null, AccountState.ACTIVE, 1L, Instant.now())));
        // The caller's token still says ADMIN; the database no longer does.
        caller = new AuthenticatedAccount(
                ACCOUNT_ID, Set.of("USER", "ADMIN"), "jti-1", Instant.now(), Instant.now().plus(Duration.ofMinutes(10)));
        when(accountService.currentRoles(ACCOUNT_ID)).thenReturn(Optional.of(Set.of("USER")));
        when(denyList.revokeIfActive(anyString(), any())).thenReturn(true);
    }

    @Test
    void refreshedTokenCarriesTheRolesStoredNowNotTheOldTokensClaim() {
        var response = controller.refresh(caller);

        assertThat(jwtService.parse(response.getBody().token()).roles()).containsExactly("USER");
    }

    @Test
    void aSecondRefreshWithTheSameTokenIsRejected() {
        when(denyList.revokeIfActive("jti-1", caller.expiresAt())).thenReturn(true, false);

        controller.refresh(caller);

        assertThatThrownBy(() -> controller.refresh(caller))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
        verify(denyList, times(2)).revokeIfActive("jti-1", caller.expiresAt());
    }

    @Test
    void aLostClaimNeverSetsACookie() {
        when(denyList.revokeIfActive(anyString(), any())).thenReturn(false);

        assertThatThrownBy(() -> controller.refresh(caller)).isInstanceOf(ResponseStatusException.class);
    }
}

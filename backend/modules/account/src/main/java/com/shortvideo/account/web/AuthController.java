package com.shortvideo.account.web;

import com.shortvideo.account.api.AccountView;
import com.shortvideo.account.domain.AccountEntity;
import com.shortvideo.account.domain.AccountExceptions;
import com.shortvideo.account.domain.AccountService;
import com.shortvideo.shared.security.AuthenticatedAccount;
import com.shortvideo.shared.security.JwtService;
import com.shortvideo.shared.security.SessionCookies;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Auth")
public class AuthController {

    private final AccountService accountService;
    private final JwtService jwtService;
    private final SessionCookies sessionCookies;

    public AuthController(AccountService accountService, JwtService jwtService, SessionCookies sessionCookies) {
        this.accountService = accountService;
        this.jwtService = jwtService;
        this.sessionCookies = sessionCookies;
    }

    /**
     * Sets the token both ways: body for the SPA's bearer header, HttpOnly cookie
     * for the media gateway, which never sees a header (Rule 17).
     */
    @PostMapping("/login")
    @Operation(summary = "Log in and open a session")
    public ResponseEntity<AccountDtos.LoginResponse> login(
            @Valid @RequestBody AccountDtos.LoginRequest request) {

        AccountEntity account = accountService.authenticate(request.email(), request.password());
        JwtService.IssuedToken issued =
                jwtService.issue(account.getAccountId().toString(), accountService.rolesOf(account));

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, sessionCookies.session(issued.token(), issued.expiresAt()).toString())
                .body(new AccountDtos.LoginResponse(
                        account.getAccountId().toString(), issued.token(), issued.expiresAt()));
    }

    /**
     * A client checks this once on load instead of assuming "signed out" after
     * every page refresh — the session cookie already carries a valid identity
     * across reloads; only the client's own in-memory state was ever lost.
     */
    @GetMapping("/me")
    @Operation(summary = "Who the current session cookie/bearer token belongs to")
    public AccountDtos.MeResponse me(@AuthenticationPrincipal AuthenticatedAccount caller) {
        AccountView account = accountService
                .find(caller.accountId())
                .orElseThrow(() -> new AccountExceptions.AccountNotFound("No such account"));
        return AccountDtos.MeResponse.from(account, caller.roles());
    }

    @PostMapping("/logout")
    @Operation(summary = "Clear the session cookie")
    public ResponseEntity<Void> logout() {
        // Playback cookies are path-scoped and short-lived; Milestone 2 clears them
        // here too once playback sessions exist.
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, sessionCookies.clearSession().toString())
                .build();
    }
}

package com.shortvideo.account.web;

import com.shortvideo.account.api.AccountView;
import com.shortvideo.account.domain.AccountEntity;
import com.shortvideo.account.domain.AccountExceptions;
import com.shortvideo.account.domain.AccountService;
import com.shortvideo.account.domain.Emails;
import com.shortvideo.shared.security.AuthenticatedAccount;
import com.shortvideo.shared.security.ClientIpResolver;
import com.shortvideo.shared.security.JwtService;
import com.shortvideo.shared.security.LoginRateLimiter;
import com.shortvideo.shared.security.SessionCookies;
import com.shortvideo.shared.security.SessionTokenDenyList;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final AccountService accountService;
    private final JwtService jwtService;
    private final SessionCookies sessionCookies;
    private final SessionTokenDenyList denyList;
    private final LoginRateLimiter rateLimiter;
    private final ClientIpResolver clientIpResolver;

    public AuthController(
            AccountService accountService,
            JwtService jwtService,
            SessionCookies sessionCookies,
            SessionTokenDenyList denyList,
            LoginRateLimiter rateLimiter,
            ClientIpResolver clientIpResolver) {
        this.accountService = accountService;
        this.jwtService = jwtService;
        this.sessionCookies = sessionCookies;
        this.denyList = denyList;
        this.rateLimiter = rateLimiter;
        this.clientIpResolver = clientIpResolver;
    }

    /**
     * Sets the token both ways: body for the SPA's bearer header, HttpOnly cookie
     * for the media gateway, which never sees a header (Rule 17).
     */
    @PostMapping("/login")
    @Operation(summary = "Log in and open a session")
    public ResponseEntity<AccountDtos.LoginResponse> login(
            @Valid @RequestBody AccountDtos.LoginRequest request, HttpServletRequest httpRequest) {

        // Normalised here, the same way authenticate() normalises internally, so the
        // rate limiter keys on the identity the account lookup actually matches —
        // otherwise cycling letter case in the address gives each variant its own
        // counter and the per-account limit never accumulates.
        String email = Emails.normalise(request.email());
        // Checked before authenticate(), so a throttled attempt never reaches the
        // ~100ms BCrypt comparison that makes this endpoint expensive to serve.
        rateLimiter.checkAllowed(clientIpResolver.resolve(httpRequest), email);

        AccountEntity account;
        try {
            account = accountService.authenticate(email, request.password());
        } catch (RuntimeException rejected) {
            rateLimiter.recordFailure(email);
            throw rejected;
        }
        rateLimiter.recordSuccess(email);

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

    /**
     * Extends an active session without asking for the password again.
     *
     * <p>A 30-minute TTL with no way to extend it means an active user is signed out
     * mid-task, which is why sessions get lengthened until they stop protecting
     * anything. Renewal keeps the TTL short while letting continued use carry it
     * forward.
     *
     * <p>Two properties keep that from becoming an unbounded session. The old token
     * is revoked as part of renewing, so a renewed session is a replacement rather
     * than an additional live credential and a stolen token cannot be kept alive in
     * parallel with the legitimate one. And renewal runs through the same filter as
     * every other request, so a suspended account or a signed-out token cannot renew
     * at all — the revocation check has already rejected it before this method runs.
     *
     * <p>Deliberately a separate endpoint rather than a sliding cookie refreshed on
     * every response: the client decides when to extend, the server is not
     * re-issuing credentials on unrelated requests, and the act of extending a
     * session is one auditable event instead of a side effect of traffic.
     */
    @PostMapping("/refresh")
    @Operation(summary = "Exchange the current session for a fresh one; revokes the old token")
    public ResponseEntity<AccountDtos.LoginResponse> refresh(
            @AuthenticationPrincipal AuthenticatedAccount caller) {

        AccountView account = accountService
                .find(caller.accountId())
                .orElseThrow(() -> new AccountExceptions.AccountNotFound("No such account"));

        // Roles come from the DB, not caller.roles(): copying them token-to-token would
        // let a demoted admin keep ROLE_ADMIN indefinitely by refreshing before expiry.
        var roles = accountService
                .currentRoles(caller.accountId())
                .orElseThrow(() -> new AccountExceptions.AccountNotFound("No such account"));
        // Claim the old token first, atomically: a concurrent refresh with the same
        // token (double-submit, retry) loses here instead of minting a second live
        // credential. Signing is local, so failing after the claim is far less likely
        // than the race this closes.
        if (!denyList.revokeIfActive(caller.tokenId(), caller.expiresAt())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Session already refreshed");
        }
        JwtService.IssuedToken issued = jwtService.issue(caller.accountId(), roles);

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, sessionCookies.session(issued.token(), issued.expiresAt()).toString())
                .body(new AccountDtos.LoginResponse(account.accountId(), issued.token(), issued.expiresAt()));
    }

    /**
     * Clearing the cookie only ends the session for a client that cooperates — the
     * same token is also handed to the SPA in the login response, and a stateless
     * token stays valid until it expires. Revoking the {@code jti} is what actually
     * ends the session for both transports.
     *
     * <p>Playback cookies are path-scoped to a single video and expire in minutes,
     * so they are left to lapse rather than enumerated here.
     */
    @PostMapping("/logout")
    @Operation(summary = "End the session and revoke the current token")
    public ResponseEntity<Void> logout(@AuthenticationPrincipal AuthenticatedAccount caller) {
        // The cookie is cleared unconditionally: it needs no Redis, and a caller who
        // pressed "Log out" must never be left holding a live cookie just because
        // the deny-list write below failed.
        if (caller != null) {
            try {
                denyList.revoke(caller.tokenId(), caller.expiresAt());
            } catch (RuntimeException revokeFailed) {
                log.warn("Failed to revoke token {} on logout; cookie is still cleared",
                        caller.tokenId(), revokeFailed);
            }
        }
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, sessionCookies.clearSession().toString())
                .build();
    }
}

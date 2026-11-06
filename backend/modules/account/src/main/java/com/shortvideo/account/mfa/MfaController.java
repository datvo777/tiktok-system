package com.shortvideo.account.mfa;

import com.shortvideo.account.api.AccountView;
import com.shortvideo.account.domain.AccountExceptions;
import com.shortvideo.account.domain.AccountService;
import com.shortvideo.account.api.AccountState;
import com.shortvideo.account.web.AccountDtos;
import com.shortvideo.shared.security.AuthenticatedAccount;
import com.shortvideo.shared.security.ClientIpResolver;
import com.shortvideo.shared.security.InvalidTokenException;
import com.shortvideo.shared.security.JwtService;
import com.shortvideo.shared.security.MfaAttemptLimiter;
import com.shortvideo.shared.security.MfaPendingTokens;
import com.shortvideo.shared.security.MfaProperties;
import com.shortvideo.shared.security.SessionCookies;
import com.shortvideo.shared.security.SessionTokenDenyList;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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

/**
 * The second step of signing in, and enrolling in it. Absent (404) when {@code shortvideo.mfa.enabled} is
 * false, which is also how a client finds out the factor is off.
 */
@RestController
@RequestMapping("/api/v1/auth/mfa")
@Tag(name = "Auth")
@ConditionalOnProperty(prefix = "shortvideo.mfa", name = "enabled", havingValue = "true")
public class MfaController {

    private final MfaService mfa;
    private final AccountService accounts;
    private final JwtService jwtService;
    private final SessionCookies sessionCookies;
    private final SessionTokenDenyList denyList;
    private final MfaPendingTokens pendingTokens;
    private final MfaAttemptLimiter limiter;
    private final ClientIpResolver clientIps;
    private final MfaProperties properties;

    public MfaController(
            MfaService mfa,
            AccountService accounts,
            JwtService jwtService,
            SessionCookies sessionCookies,
            SessionTokenDenyList denyList,
            MfaPendingTokens pendingTokens,
            MfaAttemptLimiter limiter,
            ClientIpResolver clientIps,
            MfaProperties properties) {
        this.mfa = mfa;
        this.accounts = accounts;
        this.jwtService = jwtService;
        this.sessionCookies = sessionCookies;
        this.denyList = denyList;
        this.pendingTokens = pendingTokens;
        this.limiter = limiter;
        this.clientIps = clientIps;
        this.properties = properties;
    }

    /** Not behind a session: the pending token from the password step is what identifies the person. */
    @PostMapping("/verify")
    @Operation(summary = "Finish signing in with a code from the authenticator, or a recovery code")
    public ResponseEntity<AccountDtos.LoginResponse> verify(
            @Valid @RequestBody MfaDtos.VerifyRequest request, HttpServletRequest httpRequest) {

        MfaPendingTokens.Pending pending;
        try {
            pending = pendingTokens.parse(request.mfaToken());
        } catch (InvalidTokenException e) {
            throw new MfaExceptions.PendingSignInInvalid("Start signing in again");
        }
        // Before the code is looked at, so a throttled or spent attempt costs nothing.
        limiter.checkAllowed(clientIps.resolve(httpRequest), pending.accountId());
        if (limiter.isSpent(pending.id())) {
            throw new MfaExceptions.PendingSignInInvalid("Start signing in again");
        }
        boolean hasCode = request.code() != null && !request.code().isBlank();
        boolean hasRecovery = request.recoveryCode() != null && !request.recoveryCode().isBlank();
        if (hasCode == hasRecovery) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Send either a code or a recovery code");
        }

        if (!mfa.verifyAtSignIn(pending.accountId(), request.code(), request.recoveryCode())) {
            limiter.recordFailure(pending.id(), pending.accountId());
            throw new MfaExceptions.InvalidCode();
        }
        limiter.recordSuccess(pending.id(), pending.accountId());

        // Five minutes may have passed since the password: the account could have been suspended since.
        AccountView account = accounts.find(pending.accountId())
                .filter(a -> a.state() == AccountState.ACTIVE)
                .orElseThrow(() -> new MfaExceptions.PendingSignInInvalid("Start signing in again"));
        Set<String> roles = accounts.currentRoles(pending.accountId())
                .orElseThrow(() -> new AccountExceptions.AccountNotFound("No such account"));

        JwtService.IssuedToken issued = jwtService.issue(
                account.accountId(), roles, Instant.now(), Set.of(AuthenticatedAccount.PASSWORD, AuthenticatedAccount.ONE_TIME_CODE));
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, sessionCookies.session(issued.token(), issued.expiresAt()).toString())
                .body(new AccountDtos.LoginResponse(account.accountId(), issued.token(), issued.expiresAt()));
    }

    @GetMapping("/status")
    @Operation(summary = "Whether this account has a second factor, and whether this session used it")
    public MfaDtos.Status status(@AuthenticationPrincipal AuthenticatedAccount caller) {
        boolean required = caller.roles().stream().anyMatch(properties.getRequiredRoles()::contains);
        return new MfaDtos.Status(mfa.hasActiveFactor(caller.accountId()), caller.hasSecondFactor(), required);
    }

    @PostMapping("/totp/enroll")
    @Operation(summary = "Start setting up an authenticator app; needs the current password")
    public MfaDtos.EnrollResponse enroll(
            @AuthenticationPrincipal AuthenticatedAccount caller, @Valid @RequestBody MfaDtos.EnrollRequest request) {
        MfaService.Enrollment enrollment;
        try {
            enrollment = mfa.startEnrollment(caller.accountId(), accounts.emailOf(caller.accountId()), request.password());
        } catch (AccountExceptions.InvalidCredentials wrong) {
            throw new MfaExceptions.PasswordIncorrect();
        }
        return new MfaDtos.EnrollResponse(enrollment.secret(), enrollment.otpauthUri());
    }

    @PostMapping("/totp/confirm")
    @Operation(summary = "Finish setting up: a code from the app turns it on and returns the recovery codes, once")
    public ResponseEntity<MfaDtos.ConfirmResponse> confirm(
            @AuthenticationPrincipal AuthenticatedAccount caller, @Valid @RequestBody MfaDtos.ConfirmRequest request) {
        var recoveryCodes = mfa.confirmEnrollment(caller.accountId(), request.code());

        // The session that enrolled predates the change, so it is replaced rather than kept: the new one carries
        // the second factor, and the login time is carried over so this does not extend the session's lifetime.
        var roles = accounts.currentRoles(caller.accountId())
                .orElseThrow(() -> new AccountExceptions.AccountNotFound("No such account"));
        denyList.revokeIfActive(caller.tokenId(), caller.expiresAt());
        JwtService.IssuedToken issued = jwtService.issue(
                caller.accountId(), roles, caller.authTime(), Set.of(AuthenticatedAccount.PASSWORD, AuthenticatedAccount.ONE_TIME_CODE));
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, sessionCookies.session(issued.token(), issued.expiresAt()).toString())
                .body(new MfaDtos.ConfirmResponse(caller.accountId(), issued.token(), issued.expiresAt(), recoveryCodes));
    }
}

package com.shortvideo.account.web;

import com.shortvideo.account.api.AccountView;
import com.shortvideo.account.domain.AccountExceptions;
import com.shortvideo.account.domain.AccountService;
import com.shortvideo.shared.security.AuthenticatedAccount;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/accounts")
@Tag(name = "Account")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @PostMapping
    @Operation(summary = "Register an account")
    public ResponseEntity<AccountDtos.AccountResponse> register(
            @Valid @RequestBody AccountDtos.RegisterRequest request) {
        AccountView view = accountService.register(request.email(), request.password(), request.displayName());
        return ResponseEntity.created(URI.create("/api/v1/accounts/" + view.accountId()))
                .body(AccountDtos.AccountResponse.from(view));
    }

    /**
     * Updates the caller's own profile. Deliberately scoped to {@code /me}
     * rather than taking an id: there is no legitimate reason for one account to
     * name another here, and not accepting the id at all is a stronger guarantee
     * than checking it.
     */
    @PatchMapping("/me")
    @Operation(summary = "Update your own display name or bio")
    public AccountDtos.AccountResponse updateProfile(
            @AuthenticationPrincipal AuthenticatedAccount caller,
            @Valid @RequestBody AccountDtos.UpdateProfileRequest request) {
        return AccountDtos.AccountResponse.from(
                accountService.updateProfile(caller.accountId(), request.displayName(), request.bio()));
    }

    /**
     * Its own endpoint rather than a field on the profile PATCH: a username is
     * the one thing other people use to find and mention you, so changing it is
     * a deliberate act with its own failure mode (taken) rather than one field
     * among several.
     */
    @PatchMapping("/me/handle")
    @Operation(summary = "Change your own username")
    public AccountDtos.AccountResponse changeHandle(
            @AuthenticationPrincipal AuthenticatedAccount caller,
            @Valid @RequestBody AccountDtos.ChangeHandleRequest request) {
        return AccountDtos.AccountResponse.from(
                accountService.changeHandle(caller.accountId(), request.handle()));
    }

    /**
     * Answers "can I have this one?" while someone types, so the form can say so
     * before they submit. Never 4xx for a malformed handle — an in-progress
     * value is not an error, it is just not available yet.
     */
    @GetMapping("/handle-available")
    @Operation(summary = "Whether a username is well-formed and free")
    public AccountDtos.HandleAvailabilityResponse handleAvailable(
            @RequestParam String handle, @AuthenticationPrincipal AuthenticatedAccount caller) {
        try {
            boolean free = accountService.isHandleAvailable(handle, caller.accountId());
            return new AccountDtos.HandleAvailabilityResponse(
                    handle, free, free ? null : "That username is taken");
        } catch (AccountExceptions.InvalidHandle malformed) {
            return new AccountDtos.HandleAvailabilityResponse(handle, false, malformed.getMessage());
        }
    }

    @PostMapping("/me/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Change your own password; requires the current one")
    public void changePassword(
            @AuthenticationPrincipal AuthenticatedAccount caller,
            @Valid @RequestBody AccountDtos.ChangePasswordRequest request) {
        accountService.changePassword(caller.accountId(), request.currentPassword(), request.newPassword());
    }

    @GetMapping("/{accountId}")
    @Operation(summary = "Read an account")
    public AccountDtos.AccountResponse get(
            @PathVariable UUID accountId, @AuthenticationPrincipal AuthenticatedAccount caller) {
        // Local MVP: a caller may read their own account. Public creator profiles
        // arrive with the profile work in Milestone 4.
        if (caller == null || !caller.accountId().equals(accountId.toString())) {
            throw new AccountExceptions.AccountNotFound("No such account");
        }
        return accountService
                .find(accountId.toString())
                .map(AccountDtos.AccountResponse::from)
                .orElseThrow(() -> new AccountExceptions.AccountNotFound("No such account"));
    }
}

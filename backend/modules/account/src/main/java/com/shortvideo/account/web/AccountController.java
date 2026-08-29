package com.shortvideo.account.web;

import com.shortvideo.account.api.AccountView;
import com.shortvideo.account.domain.AccountExceptions;
import com.shortvideo.account.domain.AccountService;
import com.shortvideo.shared.security.AuthenticatedAccount;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
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

    @GetMapping("/{accountId}")
    @Operation(summary = "Read an account")
    public AccountDtos.AccountResponse get(
            @PathVariable String accountId, @AuthenticationPrincipal AuthenticatedAccount caller) {
        // Local MVP: a caller may read their own account. Public creator profiles
        // arrive with the profile work in Milestone 4.
        if (caller == null || !caller.accountId().equals(accountId)) {
            throw new AccountExceptions.AccountNotFound("No such account");
        }
        return accountService
                .find(accountId)
                .map(AccountDtos.AccountResponse::from)
                .orElseThrow(() -> new AccountExceptions.AccountNotFound("No such account"));
    }
}

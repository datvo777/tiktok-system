package com.shortvideo.account.web;

import java.util.UUID;
import com.shortvideo.account.api.AccountState;
import com.shortvideo.account.domain.AccountService;
import com.shortvideo.shared.security.AuthenticatedAccount;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin surface. An account suspension is authoritative and, from Milestone 3,
 * drives the account revocation record that blocks every video by that creator
 * without fanning out into each video row (brief section 16).
 */
@RestController
@RequestMapping("/internal/v1/accounts")
@Tag(name = "Account (internal)")
@Validated
public class InternalAccountController {

    private final AccountService accountService;

    public InternalAccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Search accounts by an email substring")
    public AccountDtos.AdminAccountListResponse search(
            @RequestParam @NotBlank @Size(max = 320) String q,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int limit) {
        return AccountDtos.AdminAccountListResponse.from(accountService.search(q, limit));
    }

    @PostMapping("/{accountId}/suspend")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Suspend an account")
    public AccountDtos.AccountResponse suspend(
            @PathVariable UUID accountId,
            @AuthenticationPrincipal AuthenticatedAccount caller,
            @Valid @RequestBody AccountDtos.SuspendRequest request) {
        return AccountDtos.AccountResponse.from(accountService.changeState(
                accountId.toString(), AccountState.SUSPENDED, request.reason(), caller.accountId()));
    }

    @PostMapping("/{accountId}/reinstate")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Reinstate a suspended or restricted account back to active")
    public AccountDtos.AccountResponse reinstate(
            @PathVariable UUID accountId,
            @AuthenticationPrincipal AuthenticatedAccount caller,
            @RequestBody(required = false) AccountDtos.SuspendRequest request) {
        return AccountDtos.AccountResponse.from(accountService.changeState(
                accountId.toString(), AccountState.ACTIVE, request == null ? null : request.reason(), caller.accountId()));
    }
}

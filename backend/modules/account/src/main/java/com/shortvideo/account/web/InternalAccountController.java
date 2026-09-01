package com.shortvideo.account.web;

import java.util.UUID;
import com.shortvideo.account.api.AccountState;
import com.shortvideo.account.domain.AccountService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin surface. An account suspension is authoritative and, from Milestone 3,
 * drives the account revocation record that blocks every video by that creator
 * without fanning out into each video row (brief section 16).
 */
@RestController
@RequestMapping("/internal/v1/accounts")
@Tag(name = "Account (internal)")
public class InternalAccountController {

    private final AccountService accountService;

    public InternalAccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @PostMapping("/{accountId}/suspend")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Suspend an account")
    public AccountDtos.AccountResponse suspend(
            @PathVariable UUID accountId, @Valid @RequestBody AccountDtos.SuspendRequest request) {
        return AccountDtos.AccountResponse.from(
                accountService.changeState(accountId.toString(), AccountState.SUSPENDED, request.reason()));
    }
}

package com.shortvideo.account.web;

import com.shortvideo.account.api.AccountState;
import com.shortvideo.account.api.AccountView;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public final class AccountDtos {

    public record RegisterRequest(
            @NotBlank @Email @Size(max = 320) String email,
            @NotBlank @Size(min = 12, max = 200) String password,
            @NotBlank @Size(min = 1, max = 100) String displayName) {}

    public record LoginRequest(
            @NotBlank @Email @Size(max = 320) String email,
            @NotBlank @Size(max = 200) String password) {}

    public record SuspendRequest(@Size(max = 100) String reason) {}

    public record AccountResponse(
            String accountId, String displayName, AccountState state, Instant createdAt) {

        public static AccountResponse from(AccountView view) {
            return new AccountResponse(
                    view.accountId(), view.displayName(), view.state(), view.createdAt());
        }
    }

    /**
     * The token is returned in the body for the SPA's Authorization header and set
     * as an HttpOnly cookie for the media gateway (brief section 12.1).
     */
    public record LoginResponse(String accountId, String token, Instant expiresAt) {}

    private AccountDtos() {}
}

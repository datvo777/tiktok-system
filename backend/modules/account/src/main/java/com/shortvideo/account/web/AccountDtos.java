package com.shortvideo.account.web;

import com.shortvideo.account.api.AccountState;
import com.shortvideo.account.api.AccountView;
import com.shortvideo.account.domain.AdminAccountView;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Set;

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

    /**
     * Lets a client answer "am I still signed in, and as whom" on page load
     * without having to persist the accountId itself — the session cookie
     * already carries identity, this just confirms it is still valid.
     */
    public record MeResponse(String accountId, String displayName, AccountState state, java.util.Set<String> roles) {
        public static MeResponse from(AccountView view, java.util.Set<String> roles) {
            return new MeResponse(view.accountId(), view.displayName(), view.state(), roles);
        }
    }

    public record AdminAccountResponse(
            String accountId, String email, String displayName, AccountState state, Set<String> roles, Instant createdAt) {
        public static AdminAccountResponse from(AdminAccountView view) {
            return new AdminAccountResponse(
                    view.accountId(), view.email(), view.displayName(), view.state(), view.roles(), view.createdAt());
        }
    }

    public record AdminAccountListResponse(List<AdminAccountResponse> items) {
        public static AdminAccountListResponse from(List<AdminAccountView> views) {
            return new AdminAccountListResponse(views.stream().map(AdminAccountResponse::from).toList());
        }
    }

    private AccountDtos() {}
}

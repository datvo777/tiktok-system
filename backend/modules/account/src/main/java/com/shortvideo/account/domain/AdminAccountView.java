package com.shortvideo.account.domain;

import com.shortvideo.account.api.AccountState;
import java.time.Instant;
import java.util.Set;

/**
 * Admin-only read model: unlike {@link com.shortvideo.account.api.AccountView},
 * which other modules consume for authorization and deliberately omits email and
 * roles, this carries what a human moderator needs to find and act on an account.
 * Never exposed outside the account module's own admin controller.
 */
public record AdminAccountView(
        String accountId, String email, String displayName, AccountState state, Set<String> roles, Instant createdAt) {}

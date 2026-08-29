package com.shortvideo.account.api;

import java.time.Instant;

/** What other modules may see. They never touch account tables directly. */
public record AccountView(
        String accountId,
        String displayName,
        AccountState state,
        long aggregateVersion,
        Instant createdAt) {

    public boolean isEligible() {
        return state == AccountState.ACTIVE;
    }
}

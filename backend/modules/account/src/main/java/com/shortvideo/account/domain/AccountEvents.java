package com.shortvideo.account.domain;

import com.shortvideo.account.api.AccountState;

/**
 * Canonical absolute-state payloads (brief section 10).
 *
 * <p>One event per aggregate transition, carrying the complete resulting state so
 * a consumer can apply it without replaying history.
 */
public final class AccountEvents {

    public record AccountStateChanged(
            String accountId,
            String displayName,
            AccountState state,
            long aggregateVersion,
            String reason) {}

    private AccountEvents() {}
}

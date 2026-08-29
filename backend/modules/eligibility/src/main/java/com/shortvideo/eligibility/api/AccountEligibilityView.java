package com.shortvideo.eligibility.api;

import java.time.Instant;

/** Brief section 17. */
public record AccountEligibilityView(
        String accountId, String accountState, boolean isAccountEligible, long sourceVersion, Instant updatedAt) {}

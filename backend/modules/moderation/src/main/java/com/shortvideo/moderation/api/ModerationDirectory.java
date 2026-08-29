package com.shortvideo.moderation.api;

import java.util.Optional;

/**
 * Read-only access to the current moderation decision (brief section 20,
 * Milestone 5 reconciliation). A missing record means PENDING, exactly as it
 * does for eligibility (Rule 9) — the moderation module does not synthesize one.
 */
public interface ModerationDirectory {

    Optional<ModerationDecisionView> findDecision(String videoId);
}

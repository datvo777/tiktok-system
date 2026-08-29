package com.shortvideo.eligibility.api;

/**
 * Reconciliation write path (brief section 20, Milestone 5: "Projection
 * reconciliation"). Callers re-derive current state from each source module's
 * own authoritative directory and pass it here; the same version-guarded upsert
 * that normal event projection uses makes this idempotent — calling it with
 * already-current state is a silent no-op, and calling it with a state the
 * projection missed corrects the drift.
 *
 * <p>Kept separate from {@link EligibilityDirectory} because that interface is
 * documented read-only; orchestration lives in {@code backend/app}, since
 * {@code video} already depends on {@code eligibility} and a reverse edge would
 * be circular.
 */
public interface EligibilityCorrector {

    void correctVideoProcessing(
            String videoId,
            String creatorId,
            String processingState,
            Integer processingVersion,
            String durabilityState,
            String assetLifecycleState,
            String legalServingState,
            long sourceVersion);

    void correctModeration(String videoId, String creatorId, String moderationState, long sourceVersion);

    void correctPublication(
            String videoId, String creatorId, String publicationState, boolean publicationIntentRequested, long sourceVersion);

    void correctAccount(String accountId, String accountState, long sourceVersion);
}

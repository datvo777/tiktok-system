package com.shortvideo.eligibility.domain;

import com.shortvideo.eligibility.api.AccountEligibilityView;
import com.shortvideo.eligibility.api.EligibilityCorrector;
import com.shortvideo.eligibility.api.EligibilityDirectory;
import com.shortvideo.eligibility.api.VideoEligibilityView;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EligibilityProjectorService implements EligibilityDirectory, EligibilityCorrector {

    private final EligibilityRepository repository;

    public EligibilityProjectorService(EligibilityRepository repository) {
        this.repository = repository;
    }

    @Transactional
    void applyProcessing(
            String videoId,
            String creatorId,
            String processingState,
            Integer processingVersion,
            String durabilityState,
            String assetLifecycleState,
            String legalServingState,
            long sourceVersion) {
        repository.upsertProcessing(
                videoId,
                creatorId,
                processingState,
                processingVersion,
                durabilityState,
                assetLifecycleState,
                legalServingState,
                sourceVersion,
                Timestamp.from(Instant.now()));
        repository.recomputeEligibility(videoId);
    }

    @Transactional
    void applyAssetLifecycle(String videoId, String creatorId, String assetLifecycleState, long sourceVersion) {
        repository.upsertAssetLifecycle(videoId, creatorId, assetLifecycleState, sourceVersion, Timestamp.from(Instant.now()));
        repository.recomputeEligibility(videoId);
    }

    @Transactional
    void applyModeration(String videoId, String creatorId, String moderationState, long sourceVersion) {
        repository.upsertModeration(videoId, creatorId, moderationState, sourceVersion, Timestamp.from(Instant.now()));
        repository.recomputeEligibility(videoId);
    }

    @Transactional
    void applyPublication(
            String videoId,
            String creatorId,
            String publicationState,
            boolean publicationIntentRequested,
            long sourceVersion) {
        repository.upsertPublication(
                videoId, creatorId, publicationState, publicationIntentRequested, sourceVersion, Timestamp.from(Instant.now()));
        repository.recomputeEligibility(videoId);
    }

    @Transactional
    void applyAccountState(String accountId, String accountState, long sourceVersion) {
        boolean eligible = "ACTIVE".equals(accountState);
        repository.upsertAccount(
                new AccountEligibilityView(accountId, accountState, eligible, sourceVersion, Instant.now()));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<VideoEligibilityView> findVideoEligibility(String videoId) {
        return repository.findVideo(videoId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AccountEligibilityView> findAccountEligibility(String accountId) {
        return repository.findAccount(accountId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<VideoEligibilityView> findEligibleVideos(int limit) {
        return repository.findEligible(limit);
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> allTrackedVideoIds(int limit) {
        return repository.allVideoIds(limit);
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> allTrackedAccountIds(int limit) {
        return repository.allAccountIds(limit);
    }

    /**
     * Reconciliation entry points (brief section 20, Milestone 5). Reuse the same
     * version-guarded upserts normal projection uses: passing already-current
     * state is a silent no-op, so calling these on a schedule is safe.
     */
    @Override
    public void correctVideoProcessing(
            String videoId,
            String creatorId,
            String processingState,
            Integer processingVersion,
            String durabilityState,
            String assetLifecycleState,
            String legalServingState,
            long sourceVersion) {
        applyProcessing(
                videoId, creatorId, processingState, processingVersion, durabilityState, assetLifecycleState,
                legalServingState, sourceVersion);
    }

    @Override
    public void correctModeration(String videoId, String creatorId, String moderationState, long sourceVersion) {
        applyModeration(videoId, creatorId, moderationState, sourceVersion);
    }

    @Override
    public void correctPublication(
            String videoId, String creatorId, String publicationState, boolean publicationIntentRequested, long sourceVersion) {
        applyPublication(videoId, creatorId, publicationState, publicationIntentRequested, sourceVersion);
    }

    @Override
    public void correctAccount(String accountId, String accountState, long sourceVersion) {
        applyAccountState(accountId, accountState, sourceVersion);
    }
}

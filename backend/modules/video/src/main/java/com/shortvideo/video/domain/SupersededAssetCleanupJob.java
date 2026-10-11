package com.shortvideo.video.domain;

import com.shortvideo.video.api.AssetLifecycleState;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Physically removes superseded {@code processingVersion} objects from MinIO
 * (brief section 7.1, Milestone 6). Each row moves DELETE_SCHEDULED ->
 * DELETION_IN_PROGRESS -> DELETED; a crash between those two leaves a row stuck
 * in DELETION_IN_PROGRESS, which the next sweep picks up again since the purge
 * itself is idempotent (a missing object is not an error).
 */
@Component
class SupersededAssetCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(SupersededAssetCleanupJob.class);
    private static final int SWEEP_LIMIT = 200;

    private final SupersededAssetJpaRepository repository;
    private final MinioAssetPurger purger;
    private final Counter purgedCounter;
    private final Counter failedCounter;

    SupersededAssetCleanupJob(SupersededAssetJpaRepository repository, MinioAssetPurger purger, MeterRegistry meters) {
        this.repository = repository;
        this.purger = purger;
        // Failures are otherwise only logged, so a purge that has been failing for days looks
        // like one with nothing to do. A rising failed counter is what an alert keys on.
        this.purgedCounter = Counter.builder("video.cleanup.purged").register(meters);
        this.failedCounter = Counter.builder("video.cleanup.failed").register(meters);
    }

    @Scheduled(fixedDelayString = "${shortvideo.lifecycle.cleanup-interval:5m}", initialDelayString = "20s")
    void sweep() {
        // Each query is bounded, so a backlog is never loaded whole; the second only
        // fills what the first left of the sweep budget.
        List<SupersededAssetEntity> due = new ArrayList<>(repository.findByStateOrderByCreatedAtAsc(
                AssetLifecycleState.DELETE_SCHEDULED, PageRequest.of(0, SWEEP_LIMIT)));
        if (due.size() < SWEEP_LIMIT) {
            due.addAll(repository.findByStateOrderByCreatedAtAsc(
                    AssetLifecycleState.DELETION_IN_PROGRESS, PageRequest.of(0, SWEEP_LIMIT - due.size())));
        }
        int purged = 0;
        int failed = 0;
        for (SupersededAssetEntity row : due) {
            // One bad row must not abort the rest of the batch; it stays DELETION_IN_PROGRESS
            // and the next sweep retries it.
            try {
                purgeOne(row);
                purged++;
            } catch (RuntimeException e) {
                failed++;
                log.warn("Failed to purge {}: {}", row.prefix(), e.getMessage());
            }
        }
        purgedCounter.increment(purged);
        failedCounter.increment(failed);
        if (purged > 0) {
            log.info("Superseded-asset cleanup purged {} prefix{}", purged, purged == 1 ? "" : "es");
        }
    }

    /**
     * Deliberately not one transaction (and not {@code @Transactional}, which a call from
     * {@link #sweep} would bypass anyway): the in-progress mark commits before the network call,
     * and the row is only marked DELETED once the purge has actually succeeded. A purge that
     * throws leaves it DELETION_IN_PROGRESS for the next sweep.
     */
    void purgeOne(SupersededAssetEntity row) {
        row.markDeletionInProgress();
        repository.saveAndFlush(row);
        purger.purgePrefix(row.prefix());
        row.markDeleted();
        repository.saveAndFlush(row);
    }
}

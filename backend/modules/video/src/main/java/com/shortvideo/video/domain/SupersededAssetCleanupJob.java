package com.shortvideo.video.domain;

import com.shortvideo.video.api.AssetLifecycleState;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

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

    SupersededAssetCleanupJob(SupersededAssetJpaRepository repository, MinioAssetPurger purger) {
        this.repository = repository;
        this.purger = purger;
    }

    @Scheduled(fixedDelayString = "${shortvideo.lifecycle.cleanup-interval:5m}", initialDelayString = "20s")
    void sweep() {
        List<SupersededAssetEntity> due = repository.findByStateOrderByCreatedAtAsc(AssetLifecycleState.DELETE_SCHEDULED);
        due.addAll(repository.findByStateOrderByCreatedAtAsc(AssetLifecycleState.DELETION_IN_PROGRESS));
        int limit = Math.min(due.size(), SWEEP_LIMIT);
        for (int i = 0; i < limit; i++) {
            purgeOne(due.get(i));
        }
        if (limit > 0) {
            log.info("Superseded-asset cleanup purged {} version prefix{}", limit, limit == 1 ? "" : "es");
        }
    }

    @Transactional
    void purgeOne(SupersededAssetEntity row) {
        row.markDeletionInProgress();
        repository.saveAndFlush(row);
        purger.purgePrefix("processed/" + row.getVideoId() + "/" + row.getProcessingVersion() + "/");
        row.markDeleted();
        repository.saveAndFlush(row);
    }
}

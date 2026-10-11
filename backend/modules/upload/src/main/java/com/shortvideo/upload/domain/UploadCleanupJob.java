package com.shortvideo.upload.domain;

import com.shortvideo.video.api.VideoDraftRegistrar;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import java.time.Instant;
import java.util.List;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Reaps upload sessions whose presigned POST policy expired without a completed
 * upload (brief section 7.1): the client never uploaded the file, or uploaded it
 * but never called complete. Left alone, every such attempt — including
 * abandoned browser tabs and load-test runs — would keep its object in
 * MinIO forever, since {@link UploadService#complete} only rejects a late
 * completion; it never reclaims the object itself. The linked video draft
 * (created alongside the session in the same transaction, brief section 7.1)
 * is expired too, so it doesn't accumulate as a permanent CREATED ghost row
 * once its owning session is gone.
 */
@Component
class UploadCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(UploadCleanupJob.class);
    private static final int SWEEP_LIMIT = 200;

    /**
     * How long past expiry plus the completion grace a session must be before it is reaped. A
     * completion accepted just inside the grace can still be copying for a minute or so (the
     * object-store timeouts are 30s per call), and the reaper must not delete the object out
     * from under it.
     */
    static final java.time.Duration REAP_AFTER = UploadSessionEntity.COMPLETION_GRACE.plus(java.time.Duration.ofMinutes(2));

    private final UploadJpaRepository repository;
    private final MinioClient minioClient;
    private final VideoDraftRegistrar videoDraftRegistrar;
    private final TransactionTemplate transaction;
    private final String bucket;
    private final Counter reapedCounter;
    private final Counter failedCounter;

    UploadCleanupJob(
            UploadJpaRepository repository,
            MinioClient minioClient,
            VideoDraftRegistrar videoDraftRegistrar,
            TransactionTemplate transaction,
            @Value("${shortvideo.minio.bucket}") String bucket,
            MeterRegistry meters) {
        this.repository = repository;
        this.minioClient = minioClient;
        this.videoDraftRegistrar = videoDraftRegistrar;
        this.transaction = transaction;
        this.bucket = bucket;
        // Failures are only logged otherwise, so a job that has been failing for days looks
        // exactly like one with nothing to do. A rising failed counter is what an alert keys on.
        this.reapedCounter = Counter.builder("upload.cleanup.reaped").register(meters);
        this.failedCounter = Counter.builder("upload.cleanup.failed").register(meters);
    }

    // Safe to run twice at once (each step is idempotent and re-checks the row), but one
    // instance at a time avoids doing the same MinIO deletes twice. lockAtMostFor bounds how
    // long a crashed instance can hold the lock; lockAtLeastFor stops a fast sweep on one
    // instance being followed straight away by another instance's.
    @Scheduled(fixedDelayString = "${shortvideo.upload.cleanup-interval:15m}", initialDelayString = "30s")
    @SchedulerLock(name = "uploadCleanup", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
    public void sweep() {
        // Past expiry plus the completion grace, so a slow upload that started in time is
        // not reaped while it is still arriving or about to be completed.
        // Bounded in the query, not after it: a backlog must never be loaded whole.
        List<UploadSessionEntity> due = repository.findByStatusAndExpiresAtBeforeOrderByExpiresAtAsc(
                UploadStatus.PENDING, Instant.now().minus(REAP_AFTER), PageRequest.of(0, SWEEP_LIMIT));
        int reaped = 0;
        int failed = 0;
        for (UploadSessionEntity session : due) {
            // One bad session must not abort the rest of the batch; it stays PENDING-and-expired
            // and the next sweep retries it.
            try {
                switch (reapOne(session)) {
                    case REAPED -> reaped++;
                    case FAILED -> failed++;
                    case SKIPPED -> { }
                }
            } catch (RuntimeException e) {
                failed++;
                log.warn("Failed to reap expired upload session {}: {}", session.getUploadId(), e.getMessage());
            }
        }
        reapedCounter.increment(reaped);
        failedCounter.increment(failed);
        if (reaped > 0) {
            log.info("Upload cleanup reaped {} expired session{}", reaped, reaped == 1 ? "" : "s");
        }
        if (reaped == 0 && failed == due.size() && due.size() == SWEEP_LIMIT) {
            // The query takes the oldest first, so a full batch that all failed means newer
            // sessions are not being reached at all.
            log.error("Upload cleanup made no progress on a full batch of {} expired sessions", SWEEP_LIMIT);
        }
    }

    /**
     * Deletes the MinIO object first and only removes the row once that
     * succeeds, so a failed delete just leaves the row PENDING-and-expired
     * for the next sweep to retry rather than orphaning the object. The network
     * call stays outside the DB transaction so it never holds a connection; the
     * template (not a self-invoked {@code @Transactional}, which would bypass the
     * proxy) keeps expireDraft and delete atomic. A rollback after the object is
     * gone is safe: removing an absent object succeeds on the retry.
     *
     * @return what happened; {@link Outcome#SKIPPED} is not a failure, it means the session
     *     was completed or already removed while this ran
     */
    Outcome reapOne(UploadSessionEntity session) {
        String objectKey = session.getObjectKey();
        try {
            minioClient.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(objectKey).build());
        } catch (Exception e) {
            log.warn("Failed to remove expired upload object {}: {}", objectKey, e.getMessage());
            return Outcome.FAILED;
        }
        // The row was read before the MinIO call, so re-read it: a completion that landed in
        // the meantime made it COMPLETED, and that upload must keep its draft and its row.
        Outcome outcome = transaction.execute(status -> {
            UploadSessionEntity current = repository.findById(session.getUploadId()).orElse(null);
            if (current == null || current.getStatus() != UploadStatus.PENDING) {
                return Outcome.SKIPPED;
            }
            videoDraftRegistrar.expireDraft(current.getVideoId().toString());
            repository.delete(current);
            return Outcome.REAPED;
        });
        return outcome == null ? Outcome.SKIPPED : outcome;
    }

    enum Outcome { REAPED, SKIPPED, FAILED }
}

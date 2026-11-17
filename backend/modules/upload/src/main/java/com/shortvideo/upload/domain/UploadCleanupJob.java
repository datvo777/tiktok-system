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
import org.springframework.beans.factory.annotation.Autowired;
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
     * Wall-clock cap on one sweep, under the 10 minute lock so the lock cannot expire while the
     * sweep is still deleting (another instance would then start the same batch). One object
     * call can take 30s when the store hangs, so 200 sequential ones could run for over an hour.
     */
    private static final java.time.Duration SWEEP_BUDGET = java.time.Duration.ofMinutes(7);

    /**
     * Consecutive failures after which the sweep stops: the object store is most likely down, and
     * trying the other sessions would only wait out a timeout on each. Sessions not reached stay
     * due and are picked up by the next sweep.
     */
    private static final int MAX_CONSECUTIVE_FAILURES = 5;

    /**
     * How long past expiry plus the completion grace a session must be before it is reaped. A
     * completion accepted just inside the grace can still be copying for a minute or so (the
     * object-store timeouts are 30s per call), and the reaper must not delete the object out
     * from under it.
     */
    static final java.time.Duration REAP_AFTER = UploadSessionEntity.COMPLETION_GRACE.plus(java.time.Duration.ofMinutes(2));

    // A retry sooner than the sweep interval (15m by default) would only be picked up by the
    // next sweep anyway, so the base is two intervals.
    private static final java.time.Duration BASE_BACKOFF = java.time.Duration.ofMinutes(30);
    private static final java.time.Duration MAX_BACKOFF = java.time.Duration.ofHours(6);

    private final UploadJpaRepository repository;
    private final MinioClient minioClient;
    private final VideoDraftRegistrar videoDraftRegistrar;
    private final TransactionTemplate transaction;
    private final String bucket;
    private final Counter reapedCounter;
    private final Counter failedCounter;
    private final java.time.Duration sweepBudget;

    @Autowired
    UploadCleanupJob(
            UploadJpaRepository repository,
            MinioClient minioClient,
            VideoDraftRegistrar videoDraftRegistrar,
            TransactionTemplate transaction,
            @Value("${shortvideo.minio.bucket}") String bucket,
            MeterRegistry meters) {
        this(repository, minioClient, videoDraftRegistrar, transaction, bucket, meters, SWEEP_BUDGET);
    }

    UploadCleanupJob(
            UploadJpaRepository repository,
            MinioClient minioClient,
            VideoDraftRegistrar videoDraftRegistrar,
            TransactionTemplate transaction,
            String bucket,
            MeterRegistry meters,
            java.time.Duration sweepBudget) {
        this.sweepBudget = sweepBudget;
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
        Instant now = Instant.now();
        List<UploadSessionEntity> due = repository.findReapable(
                UploadStatus.PENDING, now.minus(REAP_AFTER), now, PageRequest.of(0, SWEEP_LIMIT));
        int reaped = 0;
        int failed = 0;
        int consecutiveFailures = 0;
        long deadline = System.nanoTime() + sweepBudget.toNanos();
        for (UploadSessionEntity session : due) {
            if (System.nanoTime() - deadline > 0) {
                log.warn("Upload cleanup ran out of its {} budget with sessions left; the next sweep continues", sweepBudget);
                break;
            }
            // One bad session must not abort the rest of the batch; it stays PENDING-and-expired
            // and is retried once its backoff has passed.
            boolean ok = true;
            try {
                switch (reapOne(session)) {
                    case REAPED -> reaped++;
                    case FAILED -> {
                        failed++;
                        ok = false;
                        recordFailure(session, now);
                    }
                    case SKIPPED -> { }
                }
            } catch (RuntimeException e) {
                failed++;
                ok = false;
                log.warn("Failed to reap expired upload session {}: {}", session.getUploadId(), e.getMessage());
                recordFailure(session, now);
            }
            consecutiveFailures = ok ? 0 : consecutiveFailures + 1;
            if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                log.error("Upload cleanup stopped after {} consecutive failures; the object store may be down",
                        consecutiveFailures);
                break;
            }
        }
        reapedCounter.increment(reaped);
        failedCounter.increment(failed);
        if (reaped > 0) {
            log.info("Upload cleanup reaped {} expired session{}", reaped, reaped == 1 ? "" : "s");
        }
    }

    /**
     * Pushes a failed session's next try out, doubling per failure up to {@link #MAX_BACKOFF}, so
     * it stops occupying the head of the oldest-first sweep. It is retried indefinitely: a
     * session stuck for days costs one attempt per {@code MAX_BACKOFF} and clears itself once the
     * cause does, while the failed counter keeps rising for an alert to catch. Never throws; a
     * failure to record just means the session is tried again next sweep.
     */
    private void recordFailure(UploadSessionEntity session, Instant now) {
        try {
            Instant next = now.plus(backoff(session.getReapAttempts() + 1));
            transaction.execute(status -> repository.recordReapFailure(session.getUploadId(), next));
        } catch (RuntimeException e) {
            log.warn("Could not record reap failure for upload session {}: {}", session.getUploadId(), e.getMessage());
        }
    }

    /** Delay before retry number {@code attempt} (1-based): base, 2x, 4x, ... capped. */
    static java.time.Duration backoff(int attempt) {
        // The shift is bounded so a large attempt count cannot overflow before the cap applies.
        java.time.Duration delay = BASE_BACKOFF.multipliedBy(1L << Math.min(Math.max(attempt - 1, 0), 10));
        return delay.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : delay;
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
     *     was completed, being completed, or already removed while this ran
     */
    Outcome reapOne(UploadSessionEntity session) {
        String objectKey = session.getObjectKey();
        try {
            minioClient.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(objectKey).build());
        } catch (Exception e) {
            log.warn("Failed to remove expired upload object {}: {}", objectKey, e.getMessage());
            return Outcome.FAILED;
        }
        // The row was read before the MinIO call, so re-read it under its lock: a completion that
        // landed in the meantime made it COMPLETED, and one still in flight holds the lock (the row
        // comes back empty); either way that upload must keep its draft and its row.
        Outcome outcome = transaction.execute(status -> {
            UploadSessionEntity current = repository.findForReap(session.getUploadId()).orElse(null);
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

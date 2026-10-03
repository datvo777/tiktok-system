package com.shortvideo.upload.domain;

import com.shortvideo.video.api.VideoDraftRegistrar;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import java.time.Instant;
import java.util.List;
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

    private final UploadJpaRepository repository;
    private final MinioClient minioClient;
    private final VideoDraftRegistrar videoDraftRegistrar;
    private final TransactionTemplate transaction;
    private final String bucket;

    UploadCleanupJob(
            UploadJpaRepository repository,
            MinioClient minioClient,
            VideoDraftRegistrar videoDraftRegistrar,
            TransactionTemplate transaction,
            @Value("${shortvideo.minio.bucket}") String bucket) {
        this.repository = repository;
        this.minioClient = minioClient;
        this.videoDraftRegistrar = videoDraftRegistrar;
        this.transaction = transaction;
        this.bucket = bucket;
    }

    @Scheduled(fixedDelayString = "${shortvideo.upload.cleanup-interval:15m}", initialDelayString = "30s")
    void sweep() {
        // Past expiry plus the completion grace, so a slow upload that started in time is
        // not reaped while it is still arriving or about to be completed.
        // Bounded in the query, not after it: a backlog must never be loaded whole.
        List<UploadSessionEntity> due = repository.findByStatusAndExpiresAtBeforeOrderByExpiresAtAsc(
                UploadStatus.PENDING, Instant.now().minus(UploadSessionEntity.COMPLETION_GRACE), PageRequest.of(0, SWEEP_LIMIT));
        int reaped = 0;
        for (UploadSessionEntity session : due) {
            // One bad session must not abort the rest of the batch; it stays PENDING-and-expired
            // and the next sweep retries it.
            try {
                if (reapOne(session)) {
                    reaped++;
                }
            } catch (RuntimeException e) {
                log.warn("Failed to reap expired upload session {}: {}", session.getUploadId(), e.getMessage());
            }
        }
        if (reaped > 0) {
            log.info("Upload cleanup reaped {} expired session{}", reaped, reaped == 1 ? "" : "s");
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
     * @return whether the session was reaped
     */
    boolean reapOne(UploadSessionEntity session) {
        String objectKey = session.getObjectKey();
        try {
            minioClient.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(objectKey).build());
        } catch (Exception e) {
            log.warn("Failed to remove expired upload object {}: {}", objectKey, e.getMessage());
            return false;
        }
        // The row was read before the MinIO call, so re-read it: a completion that landed in
        // the meantime made it COMPLETED, and that upload must keep its draft and its row.
        Boolean reaped = transaction.execute(status -> {
            UploadSessionEntity current = repository.findById(session.getUploadId()).orElse(null);
            if (current == null || current.getStatus() != UploadStatus.PENDING) {
                return false;
            }
            videoDraftRegistrar.expireDraft(current.getVideoId().toString());
            repository.delete(current);
            return true;
        });
        return Boolean.TRUE.equals(reaped);
    }
}

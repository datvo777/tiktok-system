package com.shortvideo.upload.domain;

import com.shortvideo.video.api.VideoDraftRegistrar;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reaps upload sessions whose presigned PUT URL expired without a completed
 * upload (brief section 7.1): the client never PUT the file, or PUT it but
 * never called complete. Left alone, every such attempt — including
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
    private final String bucket;

    UploadCleanupJob(
            UploadJpaRepository repository,
            MinioClient minioClient,
            VideoDraftRegistrar videoDraftRegistrar,
            @Value("${shortvideo.minio.bucket}") String bucket) {
        this.repository = repository;
        this.minioClient = minioClient;
        this.videoDraftRegistrar = videoDraftRegistrar;
        this.bucket = bucket;
    }

    @Scheduled(fixedDelayString = "${shortvideo.upload.cleanup-interval:15m}", initialDelayString = "30s")
    void sweep() {
        List<UploadSessionEntity> due =
                repository.findByStatusAndExpiresAtBeforeOrderByExpiresAtAsc(UploadStatus.PENDING, Instant.now());
        int limit = Math.min(due.size(), SWEEP_LIMIT);
        for (int i = 0; i < limit; i++) {
            reapOne(due.get(i));
        }
        if (limit > 0) {
            log.info("Upload cleanup reaped {} expired session{}", limit, limit == 1 ? "" : "s");
        }
    }

    /**
     * Deletes the MinIO object first and only removes the row once that
     * succeeds, so a failed delete just leaves the row PENDING-and-expired
     * for the next sweep to retry rather than orphaning the object.
     */
    @Transactional
    void reapOne(UploadSessionEntity session) {
        String objectKey = session.getObjectKey();
        try {
            minioClient.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(objectKey).build());
        } catch (Exception e) {
            log.warn("Failed to remove expired upload object {}: {}", objectKey, e.getMessage());
            return;
        }
        videoDraftRegistrar.expireDraft(session.getVideoId().toString());
        repository.delete(session);
    }
}

package com.shortvideo.upload.domain;

import com.shortvideo.shared.events.AggregateTypes;
import com.shortvideo.shared.events.EventEnvelope;
import com.shortvideo.shared.events.EventTypes;
import com.shortvideo.shared.outbox.OutboxWriter;
import com.shortvideo.video.api.VideoDraft;
import com.shortvideo.video.api.VideoDraftRegistrar;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.errors.ErrorResponseException;
import io.minio.http.Method;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UploadService {

    private static final String PRODUCER = "short-video-backend";
    private static final String MODULE = "upload";
    private static final int URL_EXPIRY_SECONDS = 15 * 60;
    private static final long DEFAULT_MIN_BYTES = 1;
    private static final long DEFAULT_MAX_BYTES = 500L * 1024 * 1024;

    private final UploadJpaRepository repository;
    private final OutboxWriter outboxWriter;
    private final VideoDraftRegistrar videoDraftRegistrar;
    private final MinioClient minioClient;
    private final String bucket;

    public UploadService(
            UploadJpaRepository repository,
            OutboxWriter outboxWriter,
            VideoDraftRegistrar videoDraftRegistrar,
            MinioClient minioClient,
            @Value("${shortvideo.minio.bucket}") String bucket) {
        this.repository = repository;
        this.outboxWriter = outboxWriter;
        this.videoDraftRegistrar = videoDraftRegistrar;
        this.minioClient = minioClient;
        this.bucket = bucket;
    }

    /**
     * One transaction: create the upload session and the video draft together, so
     * an immutable persisted owner exists before any byte is stored (brief section
     * 7.1).
     */
    @Transactional
    public UploadSessionCreated createSession(String accountId) {
        VideoDraft draft = videoDraftRegistrar.createDraft(accountId);

        UUID uploadId = UUID.randomUUID();
        String objectKey = "uploads/" + accountId + "/" + uploadId + "/original";
        Instant expiresAt = Instant.now().plusSeconds(URL_EXPIRY_SECONDS);

        UploadSessionEntity session = new UploadSessionEntity(
                uploadId,
                UUID.fromString(draft.videoId()),
                UUID.fromString(accountId),
                objectKey,
                DEFAULT_MIN_BYTES,
                DEFAULT_MAX_BYTES,
                expiresAt);
        repository.saveAndFlush(session);

        String uploadUrl = presign(objectKey);
        return new UploadSessionCreated(uploadId.toString(), draft.videoId(), uploadUrl, expiresAt);
    }

    /**
     * Idempotent and owner-checked (brief section 12.2): a redelivered completion
     * for an already-completed session returns the same result without reapplying
     * anything or emitting a second event.
     */
    @Transactional
    public UploadView complete(String uploadId, String accountId, String idempotencyKey) {
        UploadSessionEntity session = repository
                .findById(UUID.fromString(uploadId))
                .orElseThrow(() -> new UploadExceptions.UploadNotFound("No such upload"));

        if (!session.getAccountId().toString().equals(accountId)) {
            throw new UploadExceptions.NotUploadOwner("Not the owner of this upload");
        }
        if (session.getStatus() == UploadStatus.COMPLETED) {
            return toView(session); // redelivery / duplicate completion — no-op
        }
        if (session.getExpiresAt().isBefore(Instant.now())) {
            throw new UploadExceptions.UploadExpired("Upload session has expired");
        }

        long size = statSize(session.getObjectKey());
        if (!session.isSizeWithinRange(size)) {
            throw new UploadExceptions.UploadSizeOutOfRange(
                    "Uploaded object size " + size + " is outside the allowed range");
        }

        session.markCompleted(size, idempotencyKey);
        UploadSessionEntity saved = repository.saveAndFlush(session);

        var payload = new UploadEvents.UploadCompleted(
                saved.getUploadId().toString(),
                saved.getVideoId().toString(),
                saved.getAccountId().toString(),
                saved.getObjectKey(),
                size);

        outboxWriter.append(new EventEnvelope<>(
                UUID.randomUUID(),
                EventTypes.VIDEO_UPLOAD_COMPLETED,
                1,
                AggregateTypes.UPLOAD,
                saved.getUploadId().toString(),
                saved.getAggregateVersion(),
                Instant.now(),
                PRODUCER,
                MODULE,
                MDC.get("correlationId"),
                null,
                payload));

        return toView(saved);
    }

    @Transactional(readOnly = true)
    public UploadView find(String uploadId, String accountId) {
        UploadSessionEntity session = repository
                .findById(UUID.fromString(uploadId))
                .orElseThrow(() -> new UploadExceptions.UploadNotFound("No such upload"));
        if (!session.getAccountId().toString().equals(accountId)) {
            throw new UploadExceptions.UploadNotFound("No such upload");
        }
        return toView(session);
    }

    private String presign(String objectKey) {
        try {
            return minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.PUT)
                    .bucket(bucket)
                    .object(objectKey)
                    .expiry(URL_EXPIRY_SECONDS)
                    .build());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to presign upload URL", e);
        }
    }

    private long statSize(String objectKey) {
        try {
            StatObjectResponse stat =
                    minioClient.statObject(StatObjectArgs.builder().bucket(bucket).object(objectKey).build());
            return stat.size();
        } catch (ErrorResponseException notFound) {
            throw new UploadExceptions.UploadObjectMissing("No object was uploaded to " + objectKey);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to verify uploaded object", e);
        }
    }

    private static UploadView toView(UploadSessionEntity session) {
        return new UploadView(
                session.getUploadId().toString(),
                session.getVideoId().toString(),
                session.getStatus().name(),
                session.getCompletedSizeBytes(),
                session.getExpiresAt());
    }
}

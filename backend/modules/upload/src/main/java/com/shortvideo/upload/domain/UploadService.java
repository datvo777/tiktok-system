package com.shortvideo.upload.domain;

import com.shortvideo.shared.events.AggregateTypes;
import com.shortvideo.shared.events.EventEnvelope;
import com.shortvideo.shared.events.EventTypes;
import com.shortvideo.shared.outbox.OutboxWriter;
import com.shortvideo.video.api.VideoDraft;
import com.shortvideo.video.api.VideoDraftRegistrar;
import io.minio.MinioClient;
import io.minio.PostPolicy;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.errors.ErrorResponseException;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
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

    /**
     * Generous for a person — nobody legitimately uploads five videos at once from
     * a browser — and low enough that opening sessions in a loop stops being a way
     * to accumulate write allowance against the bucket.
     */
    private static final int MAX_OPEN_SESSIONS_PER_ACCOUNT = 5;

    private final UploadJpaRepository repository;
    private final OutboxWriter outboxWriter;
    private final VideoDraftRegistrar videoDraftRegistrar;
    private final MinioClient minioClient;
    private final String bucket;
    private final String minioEndpoint;

    public UploadService(
            UploadJpaRepository repository,
            OutboxWriter outboxWriter,
            VideoDraftRegistrar videoDraftRegistrar,
            MinioClient minioClient,
            @Value("${shortvideo.minio.bucket}") String bucket,
            @Value("${shortvideo.minio.endpoint}") String minioEndpoint) {
        this.repository = repository;
        this.outboxWriter = outboxWriter;
        this.videoDraftRegistrar = videoDraftRegistrar;
        this.minioClient = minioClient;
        this.bucket = bucket;
        this.minioEndpoint = minioEndpoint;
    }

    /**
     * One transaction: create the upload session and the video draft together, so
     * an immutable persisted owner exists before any byte is stored (brief section
     * 7.1).
     */
    @Transactional
    public UploadSessionCreated createSession(String accountId, String title, String description) {
        // The per-object size cap in the presigned policy bounds one upload; it does
        // not bound how many an account may have in flight. Without this, opening
        // sessions in a loop is an unbounded write allowance against the bucket, and
        // each one also creates a video draft row.
        UUID owner = UUID.fromString(accountId);
        long open = repository.countByAccountIdAndStatusAndExpiresAtAfter(
                owner, UploadStatus.PENDING, Instant.now());
        if (open >= MAX_OPEN_SESSIONS_PER_ACCOUNT) {
            throw new UploadExceptions.TooManyOpenUploads(
                    "You already have " + open + " uploads in progress. Finish or abandon one before starting another.");
        }

        VideoDraft draft = videoDraftRegistrar.createDraft(accountId, title, description);

        UUID uploadId = UUID.randomUUID();
        String objectKey = "uploads/" + accountId + "/" + uploadId + "/original";
        Instant expiresAt = Instant.now().plusSeconds(URL_EXPIRY_SECONDS);

        UploadSessionEntity session = new UploadSessionEntity(
                uploadId,
                UUID.fromString(draft.videoId()),
                owner,
                objectKey,
                DEFAULT_MIN_BYTES,
                DEFAULT_MAX_BYTES,
                expiresAt);
        repository.saveAndFlush(session);

        return new UploadSessionCreated(
                uploadId.toString(),
                draft.videoId(),
                uploadEndpoint(),
                presignPost(objectKey, DEFAULT_MAX_BYTES),
                DEFAULT_MAX_BYTES,
                expiresAt);
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

    /**
     * A presigned {@code PUT} places no ceiling on what the client uploads: the
     * {@link #DEFAULT_MAX_BYTES} check in {@link #complete} runs only after the
     * bytes are already in the object store, and a client that simply never calls
     * complete is never checked at all. A presigned POST policy carries a
     * {@code content-length-range} condition that MinIO enforces at write time, so
     * an oversized body is rejected by the object store rather than accepted and
     * audited later.
     *
     * @return the form fields the client must post, including the policy. The
     *     browser sends a multipart form to {@link #uploadEndpoint()} rather than
     *     PUTting the raw file.
     */
    private Map<String, String> presignPost(String objectKey, long maxBytes) {
        try {
            PostPolicy policy = new PostPolicy(bucket, ZonedDateTime.now().plusSeconds(URL_EXPIRY_SECONDS));
            policy.addEqualsCondition("key", objectKey);
            policy.addContentLengthRangeCondition(DEFAULT_MIN_BYTES, maxBytes);
            Map<String, String> formData = new LinkedHashMap<>(minioClient.getPresignedPostFormData(policy));
            formData.put("key", objectKey);
            return formData;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to presign upload policy", e);
        }
    }

    /** Where the presigned form is posted; the bucket is addressed path-style. */
    private String uploadEndpoint() {
        return minioEndpoint.replaceAll("/+$", "") + "/" + bucket;
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

package com.shortvideo.upload.domain;

import com.shortvideo.account.api.AccountDirectory;
import com.shortvideo.account.api.AccountView;
import com.shortvideo.shared.events.AggregateTypes;
import com.shortvideo.shared.events.EventEnvelope;
import com.shortvideo.shared.events.EventTypes;
import com.shortvideo.shared.outbox.OutboxWriter;
import com.shortvideo.video.api.VideoDraft;
import com.shortvideo.video.api.VideoDraftRegistrar;
import io.minio.CopyObjectArgs;
import io.minio.CopySource;
import io.minio.MinioClient;
import io.minio.PostPolicy;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.errors.ErrorResponseException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class UploadService {

    private static final Logger log = LoggerFactory.getLogger(UploadService.class);

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

    /**
     * Total write allowance an account may hold open at once, counted from each session's
     * declared size. The session count alone bounds it at five times the per-file maximum;
     * this lets an honest client that declares small files keep several open while one that
     * reserves the maximum is held to two.
     */
    private static final long MAX_OPEN_BYTES_PER_ACCOUNT = 1024L * 1024 * 1024;

    private final UploadJpaRepository repository;
    private final OutboxWriter outboxWriter;
    private final VideoDraftRegistrar videoDraftRegistrar;
    private final MinioClient minioClient;
    private final String bucket;
    private final String minioEndpoint;
    private final JdbcTemplate jdbc;
    private final AccountDirectory accountDirectory;
    private final UploadCreateRateLimiter createRateLimiter;

    public UploadService(
            UploadJpaRepository repository,
            OutboxWriter outboxWriter,
            VideoDraftRegistrar videoDraftRegistrar,
            MinioClient minioClient,
            @Value("${shortvideo.minio.bucket}") String bucket,
            @Value("${shortvideo.minio.endpoint}") String minioEndpoint,
            @Value("${shortvideo.minio.public-endpoint:}") String minioPublicEndpoint,
            JdbcTemplate jdbc,
            AccountDirectory accountDirectory,
            UploadCreateRateLimiter createRateLimiter) {
        this.repository = repository;
        this.outboxWriter = outboxWriter;
        this.videoDraftRegistrar = videoDraftRegistrar;
        this.minioClient = minioClient;
        this.bucket = bucket;
        this.minioEndpoint = minioPublicEndpoint == null || minioPublicEndpoint.isBlank() ? minioEndpoint : minioPublicEndpoint;
        this.jdbc = jdbc;
        this.accountDirectory = accountDirectory;
        this.createRateLimiter = createRateLimiter;
    }

    /**
     * One transaction: create the upload session and the video draft together, so
     * an immutable persisted owner exists before any byte is stored (brief section
     * 7.1).
     */
    @Transactional
    public UploadSessionCreated createSession(
            String accountId, String title, String description, Long sizeBytes, String idempotencyKey) {
        UUID owner = UUID.fromString(accountId);
        title = UploadMetadata.title(title);
        description = UploadMetadata.description(description);
        if (sizeBytes != null && (sizeBytes < DEFAULT_MIN_BYTES || sizeBytes > DEFAULT_MAX_BYTES)) {
            throw new UploadExceptions.InvalidMetadata(
                    "sizeBytes must be between " + DEFAULT_MIN_BYTES + " and " + DEFAULT_MAX_BYTES);
        }
        long maxBytes = sizeBytes == null ? DEFAULT_MAX_BYTES : sizeBytes;

        // Before the lock: this is a Redis round trip, and nothing networked is done while
        // the account lock is held.
        createRateLimiter.checkAllowed(accountId);

        // The JWT proves who the caller was when it was issued, not that the account may
        // still start new work. Unknown or non-ACTIVE denies (Rule 9).
        boolean active = accountDirectory.find(accountId).filter(AccountView::isEligible).isPresent();
        if (!active) {
            throw new UploadExceptions.AccountNotAllowedToUpload("This account cannot start uploads");
        }

        // Serialises this account's creates for the rest of the transaction, so the quota
        // count below and the insert that follows cannot interleave with another request's:
        // two parallel creates would otherwise both read 4 and both insert. Bounded wait,
        // and the only thing done under the lock is local DB work and local signing.
        lockAccount(accountId);

        String key = normalizeKey(idempotencyKey);
        if (key != null) {
            var existing = repository.findByAccountIdAndCreateIdempotencyKey(owner, key);
            if (existing.isPresent()) {
                return replay(existing.get());
            }
        }

        // The per-object size cap in the presigned policy bounds one upload; it does
        // not bound how many an account may have in flight. Without this, opening
        // sessions in a loop is an unbounded write allowance against the bucket, and
        // each one also creates a video draft row.
        long open = repository.countByAccountIdAndStatusAndExpiresAtAfter(
                owner, UploadStatus.PENDING, Instant.now());
        if (open >= MAX_OPEN_SESSIONS_PER_ACCOUNT) {
            throw new UploadExceptions.TooManyOpenUploads(
                    "You already have " + open + " uploads in progress. Finish or abandon one before starting another.");
        }

        long reservedBytes = repository.sumMaxSizeBytesOpen(owner, UploadStatus.PENDING, Instant.now());
        if (reservedBytes + maxBytes > MAX_OPEN_BYTES_PER_ACCOUNT) {
            throw new UploadExceptions.TooManyOpenUploads(
                    "Your open uploads already reserve " + reservedBytes + " bytes of the "
                            + MAX_OPEN_BYTES_PER_ACCOUNT + " allowed. Finish or abandon one, or declare a smaller sizeBytes.");
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
                maxBytes,
                expiresAt,
                key);
        repository.saveAndFlush(session);

        return new UploadSessionCreated(
                uploadId.toString(),
                draft.videoId(),
                uploadEndpoint(),
                presignPost(objectKey, maxBytes, expiresAt),
                maxBytes,
                expiresAt);
    }

    private void lockAccount(String accountId) {
        try {
            jdbc.execute("SET LOCAL lock_timeout = '5s'");
            jdbc.queryForList(
                    "SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended(?, 0))) l", accountId);
        } catch (PessimisticLockingFailureException e) {
            throw new UploadExceptions.UploadBusy("Another upload request for this account is in progress", e);
        }
    }

    /**
     * A retry of a create that already succeeded gets the same session back. The form is
     * re-signed (a signature cannot be stored sensibly) but pins the same key and the
     * original expiry, so it grants nothing the first response did not.
     */
    private UploadSessionCreated replay(UploadSessionEntity session) {
        if (session.getStatus() != UploadStatus.PENDING || session.getExpiresAt().isBefore(Instant.now())) {
            throw new UploadExceptions.IdempotencyKeyConflict(
                    "This Idempotency-Key belongs to an upload that is no longer open; use a new key");
        }
        return new UploadSessionCreated(
                session.getUploadId().toString(),
                session.getVideoId().toString(),
                uploadEndpoint(),
                presignPost(session.getObjectKey(), session.getMaxSizeBytes(), session.getExpiresAt()),
                session.getMaxSizeBytes(),
                session.getExpiresAt());
    }

    private static String normalizeKey(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        String trimmed = key.trim();
        return trimmed.length() > 200 ? trimmed.substring(0, 200) : trimmed;
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
            // Same answer as an unknown id, so ownership of an upload id is not disclosed.
            throw new UploadExceptions.UploadNotFound("No such upload");
        }
        if (session.getStatus() == UploadStatus.COMPLETED) {
            return toView(session); // redelivery / duplicate completion — no-op
        }
        if (!session.canStillComplete(Instant.now())) {
            throw new UploadExceptions.UploadExpired("Upload session has expired");
        }

        StatObjectResponse stat = stat(session.getObjectKey());
        long size = stat.size();
        if (!session.isSizeWithinRange(size)) {
            throw new UploadExceptions.UploadSizeOutOfRange(
                    "Uploaded object size " + size + " is outside the allowed range");
        }
        // The presigned POST stays usable until it expires, so the client could overwrite
        // uploads/... after this check and the transcoder would read different bytes than
        // the ones just verified. Everything downstream reads a private copy instead, made
        // only if the object is still the one we stat'ed (matchETag) and under a key no
        // presigned policy covers.
        String sourceKey = sourceKeyFor(session.getVideoId());
        copyVerified(session.getObjectKey(), sourceKey, stat.etag());
        removeAfterCommit(session.getObjectKey());

        session.markCompleted(size, idempotencyKey);
        UploadSessionEntity saved = repository.saveAndFlush(session);

        var payload = new UploadEvents.UploadCompleted(
                saved.getUploadId().toString(),
                saved.getVideoId().toString(),
                saved.getAccountId().toString(),
                sourceKey,
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
    private Map<String, String> presignPost(String objectKey, long maxBytes, Instant expiresAt) {
        try {
            PostPolicy policy = new PostPolicy(bucket, expiresAt.atZone(java.time.ZoneOffset.UTC));
            policy.addEqualsCondition("key", objectKey);
            policy.addContentLengthRangeCondition(DEFAULT_MIN_BYTES, maxBytes);
            Map<String, String> formData = new LinkedHashMap<>(minioClient.getPresignedPostFormData(policy));
            formData.put("key", objectKey);
            return formData;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to presign upload policy", e);
        }
    }

    /**
     * Where the presigned form is posted: the browser-reachable address, which can differ
     * from the one the backend connects to. The bucket is addressed path-style.
     */
    private String uploadEndpoint() {
        return minioEndpoint.replaceAll("/+$", "") + "/" + bucket;
    }

    private StatObjectResponse stat(String objectKey) {
        try {
            return minioClient.statObject(StatObjectArgs.builder().bucket(bucket).object(objectKey).build());
        } catch (ErrorResponseException notFound) {
            throw new UploadExceptions.UploadObjectMissing("No object was uploaded to " + objectKey);
        } catch (Exception e) {
            throw new UploadExceptions.StorageUnavailable("Failed to verify uploaded object", e);
        }
    }

    static String sourceKeyFor(UUID videoId) {
        return "sources/" + videoId + "/original";
    }

    /** Server-side copy, refused by the store if the object changed since {@code etag} was read. */
    private void copyVerified(String fromKey, String toKey, String etag) {
        try {
            minioClient.copyObject(CopyObjectArgs.builder()
                    .bucket(bucket)
                    .object(toKey)
                    .source(CopySource.builder().bucket(bucket).object(fromKey).matchETag(etag).build())
                    .build());
        } catch (ErrorResponseException changed) {
            throw new UploadExceptions.UploadObjectMissing(
                    "The uploaded object changed while it was being verified; upload it again");
        } catch (Exception e) {
            throw new UploadExceptions.StorageUnavailable("Failed to secure the uploaded object", e);
        }
    }

    /**
     * Only once the completion has committed: if the transaction rolled back, the client's
     * retry must still find the original to copy. A failed delete is harmless, since
     * nothing reads uploads/ any more.
     */
    private void removeAfterCommit(String objectKey) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    minioClient.removeObject(
                            RemoveObjectArgs.builder().bucket(bucket).object(objectKey).build());
                } catch (Exception e) {
                    log.warn("Could not remove upload staging object {}: {}", objectKey, e.getMessage());
                }
            }
        });
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

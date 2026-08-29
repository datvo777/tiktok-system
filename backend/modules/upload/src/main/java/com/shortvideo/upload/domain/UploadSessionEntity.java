package com.shortvideo.upload.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "upload_session", schema = "upload")
public class UploadSessionEntity {

    @Id
    @Column(name = "upload_id", nullable = false, updatable = false)
    private UUID uploadId;

    @Column(name = "video_id", nullable = false, updatable = false)
    private UUID videoId;

    @Column(name = "account_id", nullable = false, updatable = false)
    private UUID accountId;

    @Column(name = "object_key", nullable = false, updatable = false)
    private String objectKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private UploadStatus status;

    @Column(name = "min_size_bytes", nullable = false, updatable = false)
    private long minSizeBytes;

    @Column(name = "max_size_bytes", nullable = false, updatable = false)
    private long maxSizeBytes;

    @Column(name = "completed_size_bytes")
    private Long completedSizeBytes;

    @Column(name = "idempotency_key", length = 200)
    private String idempotencyKey;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    /** Optimistic concurrency and the aggregate version carried by events (Rule 10). */
    @Version
    @Column(name = "aggregate_version", nullable = false)
    private long aggregateVersion;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected UploadSessionEntity() {}

    public UploadSessionEntity(
            UUID uploadId,
            UUID videoId,
            UUID accountId,
            String objectKey,
            long minSizeBytes,
            long maxSizeBytes,
            Instant expiresAt) {
        Instant now = Instant.now();
        this.uploadId = uploadId;
        this.videoId = videoId;
        this.accountId = accountId;
        this.objectKey = objectKey;
        this.status = UploadStatus.PENDING;
        this.minSizeBytes = minSizeBytes;
        this.maxSizeBytes = maxSizeBytes;
        this.expiresAt = expiresAt;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void markCompleted(long size, String idempotencyKey) {
        this.status = UploadStatus.COMPLETED;
        this.completedSizeBytes = size;
        this.idempotencyKey = idempotencyKey;
        this.updatedAt = Instant.now();
    }

    public boolean isSizeWithinRange(long size) {
        return size >= minSizeBytes && size <= maxSizeBytes;
    }

    public UUID getUploadId() { return uploadId; }
    public UUID getVideoId() { return videoId; }
    public UUID getAccountId() { return accountId; }
    public String getObjectKey() { return objectKey; }
    public UploadStatus getStatus() { return status; }
    public long getMinSizeBytes() { return minSizeBytes; }
    public long getMaxSizeBytes() { return maxSizeBytes; }
    public Long getCompletedSizeBytes() { return completedSizeBytes; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public Instant getExpiresAt() { return expiresAt; }
    public long getAggregateVersion() { return aggregateVersion; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}

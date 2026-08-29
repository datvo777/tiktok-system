package com.shortvideo.notification.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/**
 * One row per delivered notification (brief section 20, Milestone 7). Created
 * once by a durable-inbox-guarded event reaction (so a redelivered domain event
 * has exactly one user-visible effect — the acceptance criterion "duplicate
 * notification delivery has one user-visible effect") and mutated only by
 * {@code markRead}.
 */
@Entity
@Table(name = "notification", schema = "notification")
public class NotificationEntity {

    @Id
    @Column(name = "notification_id", nullable = false, updatable = false)
    private UUID notificationId;

    @Column(name = "recipient_account_id", nullable = false, updatable = false)
    private UUID recipientAccountId;

    @Column(name = "type", nullable = false, length = 50, updatable = false)
    private String type;

    @Column(name = "message", nullable = false, length = 500, updatable = false)
    private String message;

    @Column(name = "related_video_id", updatable = false)
    private UUID relatedVideoId;

    @Column(name = "read", nullable = false)
    private boolean read;

    @Version
    @Column(name = "aggregate_version", nullable = false)
    private long aggregateVersion;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected NotificationEntity() {}

    public NotificationEntity(
            UUID notificationId, UUID recipientAccountId, String type, String message, UUID relatedVideoId) {
        Instant now = Instant.now();
        this.notificationId = notificationId;
        this.recipientAccountId = recipientAccountId;
        this.type = type;
        this.message = message;
        this.relatedVideoId = relatedVideoId;
        this.read = false;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void markRead() {
        this.read = true;
        this.updatedAt = Instant.now();
    }

    public UUID getNotificationId() { return notificationId; }
    public UUID getRecipientAccountId() { return recipientAccountId; }
    public String getType() { return type; }
    public String getMessage() { return message; }
    public UUID getRelatedVideoId() { return relatedVideoId; }
    public boolean isRead() { return read; }
    public long getAggregateVersion() { return aggregateVersion; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}

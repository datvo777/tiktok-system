package com.shortvideo.publication.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/**
 * Owns publicationState and publicationIntent (brief section 8's ownership
 * table) — the video aggregate itself does not store these.
 *
 * <p>An event-driven coordinator: {@code intent}, {@code processingReady}, and
 * {@code moderationApproved} are maintained from Kafka, and {@link #reevaluate}
 * derives the resulting state. Rejection forces {@code SUSPENDED} directly
 * ({@link #suspend}); reinstatement clears the moderation flag and lets a normal
 * reevaluation move the video back to PUBLISHED if the other prerequisites still
 * hold (brief section 18).
 */
@Entity
@Table(name = "publication", schema = "publication")
public class PublicationEntity {

    @Id
    @Column(name = "video_id", nullable = false, updatable = false)
    private UUID videoId;

    @Column(name = "owner_account_id", nullable = false, updatable = false)
    private UUID ownerAccountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 30)
    private PublicationState state;

    @Column(name = "intent", nullable = false)
    private boolean intent;

    @Column(name = "processing_ready", nullable = false)
    private boolean processingReady;

    @Column(name = "moderation_approved", nullable = false)
    private boolean moderationApproved;

    /** Optimistic concurrency and the aggregate version carried by events (Rule 10). */
    @Version
    @Column(name = "aggregate_version", nullable = false)
    private long aggregateVersion;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PublicationEntity() {}

    public PublicationEntity(UUID videoId, UUID ownerAccountId) {
        Instant now = Instant.now();
        this.videoId = videoId;
        this.ownerAccountId = ownerAccountId;
        this.state = PublicationState.PRIVATE;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public boolean requestPublish() {
        this.intent = true;
        return reevaluate();
    }

    public boolean setProcessingReady(boolean ready) {
        this.processingReady = ready;
        return reevaluate();
    }

    public boolean setModerationApproved(boolean approved) {
        this.moderationApproved = approved;
        return reevaluate();
    }

    /** Forces SUSPENDED regardless of current state; bypasses the normal prerequisite computation. */
    public boolean suspend() {
        boolean changed = this.state != PublicationState.SUSPENDED;
        this.state = PublicationState.SUSPENDED;
        this.moderationApproved = false;
        this.updatedAt = Instant.now();
        return changed;
    }

    /** Forces REMOVED regardless of current state — an admin takedown (brief section 18, Milestone 6). Terminal: never reevaluated back. */
    public boolean remove() {
        boolean changed = this.state != PublicationState.REMOVED;
        this.state = PublicationState.REMOVED;
        this.updatedAt = Instant.now();
        return changed;
    }

    private boolean reevaluate() {
        if (this.state == PublicationState.REMOVED) {
            return false; // terminal — an admin takedown is never undone by a normal prerequisite flip
        }
        PublicationState next;
        if (!intent) {
            next = PublicationState.PRIVATE;
        } else if (processingReady && moderationApproved) {
            next = PublicationState.PUBLISHED;
        } else {
            next = PublicationState.PUBLISH_PENDING;
        }
        boolean changed = next != this.state;
        this.state = next;
        this.updatedAt = Instant.now();
        return changed;
    }

    public UUID getVideoId() { return videoId; }
    public UUID getOwnerAccountId() { return ownerAccountId; }
    public PublicationState getState() { return state; }
    public boolean isIntent() { return intent; }
    public boolean isProcessingReady() { return processingReady; }
    public boolean isModerationApproved() { return moderationApproved; }
    public long getAggregateVersion() { return aggregateVersion; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}

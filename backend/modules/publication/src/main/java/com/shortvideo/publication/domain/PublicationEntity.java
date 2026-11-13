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
 * ({@link #suspend}) and SUSPENDED then holds until {@link #reinstate}, which restores the
 * approval and lets the video move back to PUBLISHED if the other prerequisites still hold
 * (brief section 18). Moderation events are applied through {@link #acceptModeration}, so a
 * decision older than the one already applied cannot undo it.
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

    /** Version of the newest moderation event applied; older ones are stale. -1 before the first. */
    @Column(name = "moderation_version", nullable = false)
    private long moderationVersion = -1;

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
        if (this.state == PublicationState.REMOVED) {
            return false; // terminal: do not record an intent that can never take effect
        }
        refuseWhileSuspended();
        boolean intentChanged = !this.intent;
        this.intent = true;
        return reevaluate(intentChanged);
    }

    /**
     * The owner taking their video back out of the feed. Publication intent is
     * one of the prerequisites {@link #reevaluate(boolean)} already computes over, so
     * withdrawing it is the exact inverse of {@link #requestPublish()} and needs
     * no separate state: with no intent the machine settles on PRIVATE.
     *
     * <p>Not a takedown. Moderation approval and processing readiness are left
     * untouched, so a later re-publish does not have to be reviewed again — and,
     * unlike {@link #remove()}, this is reversible.
     */
    public boolean withdrawPublish() {
        if (this.state == PublicationState.REMOVED) {
            return false;
        }
        refuseWhileSuspended();
        boolean intentChanged = this.intent;
        this.intent = false;
        return reevaluate(intentChanged);
    }

    public boolean setProcessingReady(boolean ready) {
        boolean flagChanged = this.processingReady != ready;
        this.processingReady = ready;
        return reevaluate(flagChanged);
    }

    public boolean setModerationApproved(boolean approved) {
        boolean flagChanged = this.moderationApproved != approved;
        this.moderationApproved = approved;
        return reevaluate(flagChanged);
    }

    /**
     * Approved and rejected do not commute, so moderation events are ordered by the moderation
     * aggregate version rather than by arrival. Returns false for a decision no newer than the one
     * already applied, which the caller must then ignore.
     */
    public boolean acceptModeration(long version) {
        if (version <= this.moderationVersion) {
            return false;
        }
        this.moderationVersion = version;
        return true;
    }

    /**
     * The only way out of SUSPENDED: moderation restored its approval. The video goes wherever the
     * other prerequisites put it, which may be PUBLISHED again.
     */
    public boolean reinstate() {
        if (this.state != PublicationState.SUSPENDED) {
            return setModerationApproved(true);
        }
        this.moderationApproved = true;
        this.state = derive();
        this.updatedAt = Instant.now();
        return true;
    }

    /**
     * Reevaluation would overwrite SUSPENDED with PRIVATE or PUBLISH_PENDING, so the owner would
     * lose the fact that an admin suspended the video. Only moderation leaves SUSPENDED.
     */
    private void refuseWhileSuspended() {
        if (this.state == PublicationState.SUSPENDED) {
            throw new PublicationExceptions.PublicationSuspended("This video is suspended");
        }
    }

    /**
     * Forces SUSPENDED from any state but REMOVED; bypasses the normal prerequisite computation.
     * REMOVED is terminal, so a rejection arriving after a takedown must not bring the video back
     * as SUSPENDED (and so reachable by reinstatement).
     */
    public boolean suspend() {
        if (this.state == PublicationState.REMOVED) {
            return false;
        }
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

    /**
     * @param inputChanged whether the caller changed intent or a prerequisite flag. {@code updatedAt}
     *     moves only when something did, so a repeated call dirties nothing: no write, no version bump.
     */
    private boolean reevaluate(boolean inputChanged) {
        if (this.state == PublicationState.REMOVED) {
            return false; // terminal — an admin takedown is never undone by a normal prerequisite flip
        }
        if (this.state == PublicationState.SUSPENDED) {
            // Held until reinstatement: a late processing signal or a stray approval records its
            // flag but must not lift a rejection.
            if (inputChanged) {
                this.updatedAt = Instant.now();
            }
            return false;
        }
        PublicationState next = derive();
        boolean changed = next != this.state;
        this.state = next;
        if (changed || inputChanged) {
            this.updatedAt = Instant.now();
        }
        return changed;
    }

    private PublicationState derive() {
        if (!intent) {
            return PublicationState.PRIVATE;
        }
        return processingReady && moderationApproved ? PublicationState.PUBLISHED : PublicationState.PUBLISH_PENDING;
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
    public long getModerationVersion() { return moderationVersion; }
}

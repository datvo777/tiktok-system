package com.shortvideo.appeal.domain;

import com.shortvideo.appeal.api.AppealState;
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
 * One row per video (same convention as moderation and publication): a creator
 * may appeal a rejection more than once over the video's lifetime, so the row is
 * reused across cycles rather than accumulating a history table (brief section
 * 18, Milestone 6).
 */
@Entity
@Table(name = "appeal", schema = "appeal")
public class AppealEntity {

    @Id
    @Column(name = "video_id", nullable = false, updatable = false)
    private UUID videoId;

    @Column(name = "creator_id", nullable = false, updatable = false)
    private UUID creatorId;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 30)
    private AppealState state;

    @Column(name = "reason", length = 1000)
    private String reason;

    @Column(name = "decision_reason", length = 1000)
    private String decisionReason;

    /** Optimistic concurrency and the aggregate version carried by events (Rule 10). */
    @Version
    @Column(name = "aggregate_version", nullable = false)
    private long aggregateVersion;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AppealEntity() {}

    public AppealEntity(UUID videoId, UUID creatorId) {
        Instant now = Instant.now();
        this.videoId = videoId;
        this.creatorId = creatorId;
        this.state = AppealState.NONE;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** NONE/DENIED -> UNDER_APPEAL. Only a fresh or previously-denied appeal may be resubmitted. */
    public boolean submit(String reason) {
        if (state != AppealState.NONE && state != AppealState.DENIED) {
            return false;
        }
        this.state = AppealState.UNDER_APPEAL;
        this.reason = reason;
        this.decisionReason = null;
        this.updatedAt = Instant.now();
        return true;
    }

    /** UNDER_APPEAL/REVIEWING -> APPROVED. */
    public boolean approve(String decisionReason) {
        if (state != AppealState.UNDER_APPEAL && state != AppealState.REVIEWING) {
            return false;
        }
        this.state = AppealState.APPROVED;
        this.decisionReason = decisionReason;
        this.updatedAt = Instant.now();
        return true;
    }

    /** UNDER_APPEAL/REVIEWING -> DENIED. */
    public boolean deny(String decisionReason) {
        if (state != AppealState.UNDER_APPEAL && state != AppealState.REVIEWING) {
            return false;
        }
        this.state = AppealState.DENIED;
        this.decisionReason = decisionReason;
        this.updatedAt = Instant.now();
        return true;
    }

    public UUID getVideoId() { return videoId; }
    public UUID getCreatorId() { return creatorId; }
    public AppealState getState() { return state; }
    public String getReason() { return reason; }
    public String getDecisionReason() { return decisionReason; }
    public long getAggregateVersion() { return aggregateVersion; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}

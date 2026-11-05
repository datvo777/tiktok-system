package com.shortvideo.appeal.domain;

import com.shortvideo.appeal.api.AppealState;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Persistable;

/**
 * One row per video (same convention as moderation and publication): a creator
 * may appeal a rejection more than once over the video's lifetime, so the row is
 * reused across cycles rather than accumulating a history table (brief section
 * 18, Milestone 6).
 */
@Entity
@Table(name = "appeal", schema = "appeal")
public class AppealEntity implements Persistable<UUID> {

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

    /** Moderation aggregate version of the rejection this appeal was filed against; 0 before the first. */
    @Column(name = "moderation_version", nullable = false)
    private long moderationVersion;

    /** Optimistic concurrency and the aggregate version carried by events (Rule 10). */
    @Version
    @Column(name = "aggregate_version", nullable = false)
    private long aggregateVersion;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * The id is assigned, not generated, and the version is a primitive, so Spring Data cannot tell a
     * new row from an existing one and would {@code merge}. Two first appeals racing would then both
     * succeed, the later one silently overwriting the earlier on the row it just read. Saying so
     * explicitly makes a new row an INSERT, so the primary key refuses the second.
     */
    @Transient
    private boolean isNew;

    protected AppealEntity() {}

    public AppealEntity(UUID videoId, UUID creatorId) {
        Instant now = Instant.now();
        this.videoId = videoId;
        this.creatorId = creatorId;
        this.state = AppealState.NONE;
        this.isNew = true;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /**
     * NONE/DENIED/APPROVED -> UNDER_APPEAL. A decided appeal is finished, so it may be opened again: a
     * denied one because the creator may try once more, an approved one because the video can be
     * rejected again after it was reinstated. The caller has already checked the video is rejected now,
     * and passes the version of that rejection so a decision can later be matched to it.
     */
    public boolean submit(String reason, long moderationVersion) {
        if (state != AppealState.NONE && state != AppealState.DENIED && state != AppealState.APPROVED) {
            return false;
        }
        this.state = AppealState.UNDER_APPEAL;
        this.reason = reason;
        this.moderationVersion = moderationVersion;
        this.decisionReason = null;
        this.updatedAt = Instant.now();
        return true;
    }

    public boolean isAwaitingDecision() {
        return state == AppealState.UNDER_APPEAL || state == AppealState.REVIEWING;
    }

    /** UNDER_APPEAL/REVIEWING -> APPROVED. */
    public boolean approve(String decisionReason) {
        if (!isAwaitingDecision()) {
            return false;
        }
        this.state = AppealState.APPROVED;
        this.decisionReason = decisionReason;
        this.updatedAt = Instant.now();
        return true;
    }

    /** UNDER_APPEAL/REVIEWING -> DENIED. */
    public boolean deny(String decisionReason) {
        if (!isAwaitingDecision()) {
            return false;
        }
        this.state = AppealState.DENIED;
        this.decisionReason = decisionReason;
        this.updatedAt = Instant.now();
        return true;
    }

    @Override
    public UUID getId() { return videoId; }

    @Override
    public boolean isNew() { return isNew; }

    @PostPersist
    @PostLoad
    void markNotNew() { this.isNew = false; }

    public UUID getVideoId() { return videoId; }
    public UUID getCreatorId() { return creatorId; }
    public AppealState getState() { return state; }
    public String getReason() { return reason; }
    public String getDecisionReason() { return decisionReason; }
    public long getModerationVersion() { return moderationVersion; }
    public long getAggregateVersion() { return aggregateVersion; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}

package com.shortvideo.moderation.domain;

import com.shortvideo.moderation.api.PolicyCategory;
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
@Table(name = "moderation_record", schema = "moderation")
public class ModerationEntity {

    @Id
    @Column(name = "video_id", nullable = false, updatable = false)
    private UUID videoId;

    @Column(name = "creator_id", nullable = false, updatable = false)
    private UUID creatorId;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 30)
    private ModerationState state;

    /** Optional elaboration. The authoritative reason is {@link #policyCategory}. */
    @Column(name = "reason", length = 200)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "policy_category", length = 40)
    private PolicyCategory policyCategory;

    /**
     * Denormalised from {@link PolicyCategory#tier()} so severity can be filtered
     * and ordered in SQL without teaching the database the enum's mapping.
     */
    @Column(name = "policy_tier", length = 4)
    private String policyTier;

    /** Optimistic concurrency and the aggregate version carried by events (Rule 10). */
    @Version
    @Column(name = "aggregate_version", nullable = false)
    private long aggregateVersion;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ModerationEntity() {}

    public ModerationEntity(UUID videoId, UUID creatorId) {
        Instant now = Instant.now();
        this.videoId = videoId;
        this.creatorId = creatorId;
        this.state = ModerationState.PENDING;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** PENDING -> APPROVED, or REJECTED -> REINSTATED (brief section 18 ties reinstatement to appeal approval; the local admin action is the same transition). */
    public boolean approve() {
        boolean wasRejected = this.state == ModerationState.REJECTED;
        this.state = wasRejected ? ModerationState.REINSTATED : ModerationState.APPROVED;
        // The prior rejection's classification is cleared with it: leaving it
        // behind would make an approved video look like it still carries a
        // policy finding in every count that groups by category.
        this.reason = null;
        this.policyCategory = null;
        this.policyTier = null;
        this.updatedAt = Instant.now();
        return wasRejected;
    }

    public void reject(PolicyCategory policyCategory, String reason) {
        this.state = ModerationState.REJECTED;
        this.policyCategory = policyCategory;
        this.policyTier = policyCategory.tier();
        this.reason = reason;
        this.updatedAt = Instant.now();
    }

    public UUID getVideoId() { return videoId; }
    public UUID getCreatorId() { return creatorId; }
    public ModerationState getState() { return state; }
    public String getReason() { return reason; }
    public PolicyCategory getPolicyCategory() { return policyCategory; }
    public String getPolicyTier() { return policyTier; }
    public long getAggregateVersion() { return aggregateVersion; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}

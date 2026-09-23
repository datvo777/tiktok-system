package com.shortvideo.video.domain;

import com.shortvideo.video.api.AssetLifecycleState;
import com.shortvideo.video.api.DurabilityState;
import com.shortvideo.video.api.LegalServingState;
import com.shortvideo.video.api.ProcessingState;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "video", schema = "video")
public class VideoEntity {

    @Id
    @Column(name = "video_id", nullable = false, updatable = false)
    private UUID videoId;

    @Column(name = "owner_account_id", nullable = false, updatable = false)
    private UUID ownerAccountId;

    @Column(name = "title", length = 150, updatable = false)
    private String title;

    @Column(name = "description", length = 2000, updatable = false)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "processing_state", nullable = false, length = 30)
    private ProcessingState processingState;

    @Column(name = "processing_version")
    private Integer processingVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "durability_state", nullable = false, length = 30)
    private DurabilityState durabilityState;

    @Enumerated(EnumType.STRING)
    @Column(name = "asset_lifecycle_state", nullable = false, length = 30)
    private AssetLifecycleState assetLifecycleState;

    @Enumerated(EnumType.STRING)
    @Column(name = "legal_serving_state", nullable = false, length = 30)
    private LegalServingState legalServingState;

    @Column(name = "failure_class", length = 20)
    private String failureClass;

    @Column(name = "source_object_key", length = 500)
    private String sourceObjectKey;

    @Column(name = "master_playlist_key", length = 500)
    private String masterPlaylistKey;

    @Convert(converter = StringListJsonConverter.class)
    @Column(name = "variant_playlists")
    private List<String> variantPlaylists = List.of();

    @Column(name = "segment_count")
    private Integer segmentCount;

    @Column(name = "duration_seconds")
    private Double durationSeconds;

    /** Optimistic concurrency and the aggregate version carried by events (Rule 10). */
    @Version
    @Column(name = "aggregate_version", nullable = false)
    private long aggregateVersion;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected VideoEntity() {}

    public VideoEntity(UUID videoId, UUID ownerAccountId, String title, String description) {
        Instant now = Instant.now();
        this.videoId = videoId;
        this.ownerAccountId = ownerAccountId;
        this.title = title;
        this.description = description;
        this.processingState = ProcessingState.CREATED;
        this.durabilityState = DurabilityState.PENDING;
        this.assetLifecycleState = AssetLifecycleState.ACTIVE;
        this.legalServingState = LegalServingState.CLEAR;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** CREATED -> TRANSCODING, assigning the next processingVersion (brief section 7.1). */
    public int dispatchProcessing(String sourceObjectKey) {
        int nextVersion = this.processingVersion == null ? 1 : this.processingVersion + 1;
        this.sourceObjectKey = sourceObjectKey;
        this.processingVersion = nextVersion;
        this.processingState = ProcessingState.TRANSCODING;
        this.failureClass = null;
        this.updatedAt = Instant.now();
        return nextVersion;
    }

    /** TRANSCODING -> READY + DURABLE (local simulation, brief section 7, Rule 14). */
    public void markReady(
            String masterPlaylistKey, List<String> variantPlaylists, int segmentCount, double durationSeconds) {
        this.processingState = ProcessingState.READY;
        this.durabilityState = DurabilityState.DURABLE;
        this.masterPlaylistKey = masterPlaylistKey;
        this.variantPlaylists = variantPlaylists;
        this.segmentCount = segmentCount;
        this.durationSeconds = durationSeconds;
        this.updatedAt = Instant.now();
    }

    public void markFailed(String failureClass) {
        this.processingState = ProcessingState.FAILED;
        this.failureClass = failureClass;
        this.updatedAt = Instant.now();
    }

    /**
     * CREATED -> EXPIRED: the owning upload session's presigned URL expired
     * without a completed upload, so this draft will never receive a source
     * object. A no-op once processing has actually started, so a completed
     * upload racing the reaper is never downgraded.
     */
    public boolean expireDraft() {
        if (this.processingState != ProcessingState.CREATED) {
            return false;
        }
        this.processingState = ProcessingState.EXPIRED;
        this.updatedAt = Instant.now();
        return true;
    }

    /** ACTIVE -> REJECTED_RETAINED: the asset stays in place pending a possible appeal (brief section 18, Milestone 6). */
    public boolean retainAsRejected() {
        if (this.assetLifecycleState != AssetLifecycleState.ACTIVE) {
            return false;
        }
        this.assetLifecycleState = AssetLifecycleState.REJECTED_RETAINED;
        this.updatedAt = Instant.now();
        return true;
    }

    /**
     * REJECTED_RETAINED/QUARANTINED -> ACTIVE, only if the underlying assets are
     * still verifiably present (brief section 18: "asset lifecycle returns to
     * ACTIVE if valid"). An invalid restore is a no-op, not an error: the video
     * stays exactly where it was.
     */
    public boolean restoreIfValid(boolean assetsValid) {
        if (this.assetLifecycleState != AssetLifecycleState.REJECTED_RETAINED
                && this.assetLifecycleState != AssetLifecycleState.QUARANTINED) {
            return false;
        }
        if (!assetsValid) {
            return false;
        }
        this.assetLifecycleState = AssetLifecycleState.ACTIVE;
        this.updatedAt = Instant.now();
        return true;
    }

    /** ACTIVE/REJECTED_RETAINED -> QUARANTINED: an admin lifecycle hold independent of moderation. */
    public boolean quarantine() {
        if (this.assetLifecycleState == AssetLifecycleState.QUARANTINED
                || this.assetLifecycleState == AssetLifecycleState.DELETE_SCHEDULED
                || this.assetLifecycleState == AssetLifecycleState.DELETION_IN_PROGRESS
                || this.assetLifecycleState == AssetLifecycleState.DELETED) {
            return false;
        }
        this.assetLifecycleState = AssetLifecycleState.QUARANTINED;
        this.updatedAt = Instant.now();
        return true;
    }

    /** Any non-terminal state -> DELETE_SCHEDULED: an admin "remove video" action (brief section 18). */
    public boolean scheduleForDeletion() {
        if (this.assetLifecycleState == AssetLifecycleState.DELETE_SCHEDULED
                || this.assetLifecycleState == AssetLifecycleState.DELETION_IN_PROGRESS
                || this.assetLifecycleState == AssetLifecycleState.DELETED) {
            return false;
        }
        this.assetLifecycleState = AssetLifecycleState.DELETE_SCHEDULED;
        this.updatedAt = Instant.now();
        return true;
    }

    /**
     * TRANSCODING dispatch for a video that already has a READY version
     * (reprocessing, brief section 7.1). The previous version's assets are left
     * in place; the caller is responsible for scheduling their cleanup with the
     * old prefix captured before this call overwrites it.
     */
    public int dispatchReprocessing(String sourceObjectKey) {
        int nextVersion = this.processingVersion == null ? 1 : this.processingVersion + 1;
        this.sourceObjectKey = sourceObjectKey;
        this.processingVersion = nextVersion;
        this.processingState = ProcessingState.TRANSCODING;
        this.durabilityState = DurabilityState.PENDING;
        this.failureClass = null;
        this.updatedAt = Instant.now();
        return nextVersion;
    }

    public UUID getVideoId() { return videoId; }
    public UUID getOwnerAccountId() { return ownerAccountId; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public ProcessingState getProcessingState() { return processingState; }
    public Integer getProcessingVersion() { return processingVersion; }
    public DurabilityState getDurabilityState() { return durabilityState; }
    public AssetLifecycleState getAssetLifecycleState() { return assetLifecycleState; }
    public LegalServingState getLegalServingState() { return legalServingState; }
    public String getFailureClass() { return failureClass; }
    public String getSourceObjectKey() { return sourceObjectKey; }
    public String getMasterPlaylistKey() { return masterPlaylistKey; }
    public List<String> getVariantPlaylists() { return variantPlaylists; }
    public Integer getSegmentCount() { return segmentCount; }
    public Double getDurationSeconds() { return durationSeconds; }
    public long getAggregateVersion() { return aggregateVersion; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}

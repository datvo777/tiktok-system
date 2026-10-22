package com.shortvideo.video.domain;

import com.shortvideo.video.api.AssetLifecycleState;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Tracks one superseded {@code processingVersion}'s object prefix pending
 * physical deletion from MinIO (brief section 7.1: reprocessing "leaves the old
 * [prefix] in place until the asset lifecycle workflow removes it", Milestone 6).
 * Deliberately separate from {@link VideoEntity#getAssetLifecycleState()}, which
 * tracks the CURRENT version's serving eligibility — an old version is never
 * servable regardless of this row's state, so its lifecycle here is pure storage
 * reclamation, not a playback concern.
 */
@Entity
@Table(name = "superseded_asset", schema = "video")
public class SupersededAssetEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "video_id", nullable = false, updatable = false)
    private UUID videoId;

    @Column(name = "processing_version", nullable = false, updatable = false)
    private int processingVersion;

    @Column(name = "master_playlist_key", length = 500, updatable = false)
    private String masterPlaylistKey;

    @Convert(converter = StringListJsonConverter.class)
    @Column(name = "variant_playlists", updatable = false)
    private List<String> variantPlaylists = List.of();

    /** The prefix to purge when it is not the processed prefix derived from the version; see {@link #prefix()}. */
    @Column(name = "purge_prefix", length = 600, updatable = false)
    private String purgePrefix;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 30)
    private AssetLifecycleState state;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected SupersededAssetEntity() {}

    public SupersededAssetEntity(
            UUID videoId, int processingVersion, String masterPlaylistKey, List<String> variantPlaylists) {
        Instant now = Instant.now();
        this.id = UUID.randomUUID();
        this.videoId = videoId;
        this.processingVersion = processingVersion;
        this.masterPlaylistKey = masterPlaylistKey;
        this.variantPlaylists = variantPlaylists == null ? List.of() : variantPlaylists;
        this.state = AssetLifecycleState.DELETE_SCHEDULED;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /**
     * The verified source of a removed video. A removed video is terminal (restore only
     * applies to quarantined or rejected-retained ones), so nothing can need its source again.
     * Processing version 0 is a placeholder: real versions start at 1, and this row's prefix is
     * explicit rather than derived from it.
     */
    public static SupersededAssetEntity sourceOf(UUID videoId) {
        SupersededAssetEntity row = new SupersededAssetEntity(videoId, 0, null, List.of());
        row.purgePrefix = "sources/" + videoId + "/";
        return row;
    }

    /** What the cleanup job removes for this row. */
    public String prefix() {
        return purgePrefix != null ? purgePrefix : "processed/" + videoId + "/" + processingVersion + "/";
    }

    public void markDeletionInProgress() {
        this.state = AssetLifecycleState.DELETION_IN_PROGRESS;
        this.updatedAt = Instant.now();
    }

    public void markDeleted() {
        this.state = AssetLifecycleState.DELETED;
        this.updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getVideoId() { return videoId; }
    public int getProcessingVersion() { return processingVersion; }
    public String getMasterPlaylistKey() { return masterPlaylistKey; }
    public List<String> getVariantPlaylists() { return variantPlaylists; }
    public AssetLifecycleState getState() { return state; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}

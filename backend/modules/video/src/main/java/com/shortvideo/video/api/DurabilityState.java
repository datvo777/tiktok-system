package com.shortvideo.video.api;

/**
 * Brief section 7. Local MVP produces only these two values.
 *
 * <p>{@code DURABLE} means the manifest, playlists, segments, and metadata were
 * verified in local MinIO — a local simulation label, not a multi-region
 * replication or backup claim (Rule 14).
 */
public enum DurabilityState {
    PENDING,
    DURABLE
}

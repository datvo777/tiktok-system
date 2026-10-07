package com.shortvideo.video.domain;

import com.shortvideo.video.api.ProcessingState;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Package-private by convention: only the video module uses it. */
interface VideoJpaRepository extends JpaRepository<VideoEntity, UUID> {

    Page<VideoEntity> findByOwnerAccountIdOrderByCreatedAtDesc(UUID ownerAccountId, Pageable pageable);

    /** Locks the row, so the decisions that depend on its state and on its source being present serialise. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT v FROM VideoEntity v WHERE v.videoId = :videoId")
    Optional<VideoEntity> findForUpdate(@Param("videoId") UUID videoId);

    /**
     * Videos whose processing failed for good, long enough ago that nobody is going to retry
     * them, and whose source has not yet been scheduled for purge. Any explicit-prefix purge row
     * for the video is the source's (see {@link SupersededAssetEntity#sourceOf}).
     */
    @Query("""
            SELECT v FROM VideoEntity v
            WHERE v.processingState = :state AND v.failureClass = :failureClass AND v.updatedAt < :cutoff
              AND NOT EXISTS (SELECT 1 FROM SupersededAssetEntity s
                              WHERE s.videoId = v.videoId AND s.purgePrefix IS NOT NULL)
            ORDER BY v.updatedAt ASC
            """)
    List<VideoEntity> findFailedAwaitingSourcePurge(
            @Param("state") ProcessingState state,
            @Param("failureClass") String failureClass,
            @Param("cutoff") Instant cutoff,
            Pageable pageable);

    /**
     * Videos that have been transcoding for longer than any job can run: the command or its result
     * was lost, so nothing is going to move them. Removed videos are left out, since their results
     * are discarded anyway and they would otherwise be found again on every sweep.
     */
    @Query("""
            SELECT v FROM VideoEntity v
            WHERE v.processingState = :state AND v.updatedAt < :cutoff
              AND v.assetLifecycleState NOT IN :removed
            ORDER BY v.updatedAt ASC
            """)
    List<VideoEntity> findStuckInState(
            @Param("state") ProcessingState state,
            @Param("cutoff") Instant cutoff,
            @Param("removed") java.util.Collection<com.shortvideo.video.api.AssetLifecycleState> removed,
            Pageable pageable);
}

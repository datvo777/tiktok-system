package com.shortvideo.appeal.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface AppealJpaRepository extends JpaRepository<AppealEntity, UUID> {

    List<AppealEntity> findByStateInOrderByUpdatedAtAsc(
            List<com.shortvideo.appeal.api.AppealState> states, Pageable page);

    /**
     * Pages in the order of {@code appeal_approved_sweep_idx} and reads only what that index holds, so
     * the planner never has to sort the window or visit the table. Paging by video id alone would
     * not match the index and was measured at ~15x slower on a 50,000-appeal window.
     */
    @Query("""
            SELECT a.videoId AS videoId, a.moderationVersion AS moderationVersion, a.updatedAt AS decidedAt
            FROM AppealEntity a
            WHERE a.state = com.shortvideo.appeal.api.AppealState.APPROVED
              AND a.updatedAt BETWEEN :from AND :to
              AND (a.updatedAt, a.videoId) > (:afterAt, :afterId)
            ORDER BY a.updatedAt, a.videoId
            """)
    List<ApprovedRow> findApprovedBetweenAfter(
            @Param("from") Instant from,
            @Param("to") Instant to,
            @Param("afterAt") Instant afterAt,
            @Param("afterId") UUID afterId,
            Pageable page);

    interface ApprovedRow {
        UUID getVideoId();

        long getModerationVersion();

        Instant getDecidedAt();
    }
}

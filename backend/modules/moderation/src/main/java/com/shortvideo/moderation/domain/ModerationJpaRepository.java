package com.shortvideo.moderation.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Package-private by convention: only the moderation module uses it. */
interface ModerationJpaRepository extends JpaRepository<ModerationEntity, UUID> {

    /**
     * Keyset pagination ordered by (createdAt, videoId) ascending: the admin
     * queue can grow into the thousands, so we page it instead of ever loading
     * the whole thing (unlike {@code findByStateOrderByCreatedAtAsc}, kept only
     * for existing tests that don't care about scale).
     */
    @Query("select m from ModerationEntity m where m.state = :state "
            + "and (m.createdAt > :afterCreatedAt or (m.createdAt = :afterCreatedAt and m.videoId > :afterId)) "
            + "order by m.createdAt asc, m.videoId asc")
    List<ModerationEntity> findPageAfter(
            @Param("state") ModerationState state,
            @Param("afterCreatedAt") Instant afterCreatedAt,
            @Param("afterId") UUID afterId,
            Pageable pageable);

    List<ModerationEntity> findByStateOrderByCreatedAtAsc(ModerationState state);
}

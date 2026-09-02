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
     *
     * <p>Native query: Postgres can only push the cursor condition into the
     * {@code (state, created_at, video_id)} index as a true seek when it's
     * written as a row comparison. The equivalent JPQL {@code (createdAt >
     * :x or (createdAt = :x and videoId > :y))} looks identical but the
     * planner can't recognize it as one — it falls back to filtering every
     * row from the start of the state partition, i.e. an ever-growing scan
     * as the queue drains, exactly what keyset pagination is meant to avoid.
     */
    @Query(
            value = "select * from moderation.moderation_record where state = :#{#state.name()} "
                    + "and (created_at, video_id) > (:afterCreatedAt, :afterId) "
                    + "order by created_at asc, video_id asc",
            nativeQuery = true)
    List<ModerationEntity> findPageAfter(
            @Param("state") ModerationState state,
            @Param("afterCreatedAt") Instant afterCreatedAt,
            @Param("afterId") UUID afterId,
            Pageable pageable);

    List<ModerationEntity> findByStateOrderByCreatedAtAsc(ModerationState state);
}

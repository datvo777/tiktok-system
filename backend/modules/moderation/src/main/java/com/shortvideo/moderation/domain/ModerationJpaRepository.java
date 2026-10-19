package com.shortvideo.moderation.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Package-private by convention: only the moderation module uses it. */
interface ModerationJpaRepository extends JpaRepository<ModerationEntity, UUID> {

    /**
     * The review queue: PENDING rows a person needs to look at ({@code queued_at} set), keyset
     * paged by (queuedAt, videoId) ascending so the longest-waiting item comes first. The queue
     * can grow into the thousands, so we page it instead of ever loading the whole thing.
     *
     * <p>Native query: Postgres can only push the cursor condition into the partial
     * {@code (queued_at, video_id)} index as a true seek when it's written as a row comparison.
     * The equivalent JPQL {@code (queuedAt > :x or (queuedAt = :x and videoId > :y))} looks
     * identical but the planner can't recognize it as one — it falls back to filtering every row
     * from the start of the queue, i.e. an ever-growing scan as the queue drains, exactly what
     * keyset pagination is meant to avoid.
     */
    @Query(
            value = "select * from moderation.moderation_record where state = 'PENDING' "
                    + "and queued_at is not null "
                    + "and (queued_at, video_id) > (:afterQueuedAt, :afterId) "
                    + "order by queued_at asc, video_id asc",
            nativeQuery = true)
    List<ModerationEntity> findQueuePageAfter(
            @Param("afterQueuedAt") Instant afterQueuedAt, @Param("afterId") UUID afterId, Pageable pageable);

    /**
     * Moves a held record into the review queue. A direct update rather than load-and-save so the
     * aggregate version does not move: queueing changes no business state and emits no event, and a
     * version that jumps with nothing to explain it only makes the event stream harder to read.
     * Conditional on still being PENDING and unqueued, which also makes it idempotent and lets it
     * lose a race with an approval without clobbering it.
     *
     * @return rows changed: 1 if it was queued now, 0 if it was not held
     */
    @Modifying
    @Query(
            value = "update moderation.moderation_record set queued_at = :at, queue_reason = :reason "
                    + "where video_id = :id and state = 'PENDING' and queued_at is null",
            nativeQuery = true)
    int enqueue(@Param("id") UUID id, @Param("at") Instant at, @Param("reason") String reason);

    /** Held records (PENDING, not queued) after {@code afterId}, for the sweep that catches a lost publish event. */
    @Query(
            value = "select video_id from moderation.moderation_record where state = 'PENDING' "
                    + "and queued_at is null and video_id > :afterId order by video_id asc",
            nativeQuery = true)
    List<UUID> findHeldAfter(@Param("afterId") UUID afterId, Pageable pageable);

    /**
     * This creator's decision history, for the automated pre-screen. Counted
     * rather than fetched: the screener only needs the two totals, and a creator
     * with a thousand approvals should not load a thousand rows to establish it.
     */
    long countByCreatorIdAndState(UUID creatorId, ModerationState state);
}

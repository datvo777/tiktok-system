package com.shortvideo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shortvideo.moderation.domain.ModerationService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The review queue against real PostgreSQL: which pending records a reviewer sees, in what order,
 * and that queueing one touches nothing else.
 *
 * <p>The queue and the queueing update are native SQL (a row-comparison keyset seek, and a
 * conditional update that must not move the aggregate version), which mocks cannot vouch for.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ModerationReviewQueueIT {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        TestDatabase.register(registry);
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:1");
        registry.add("shortvideo.moderation.queue-mode", () -> "INTENT_GATED");
    }

    @Autowired ModerationService moderation;
    @Autowired JdbcTemplate jdbc;

    private String videoId() {
        return UUID.randomUUID().toString();
    }

    private void withMetadata(String videoId, String creatorId) {
        // The row the metadata projector writes for video.metadata.set (EligibilityRepository.UPSERT_METADATA).
        jdbc.update(
                "INSERT INTO eligibility.video_eligibility (video_id, creator_id, title, description, processing_state,"
                        + " durability_state, asset_lifecycle_state, legal_serving_state, moderation_state, publication_state,"
                        + " publication_intent_requested, is_video_eligible, processing_version_source,"
                        + " moderation_version_source, publication_version_source, metadata_version_source, updated_at)"
                        + " VALUES (?, ?, 'morning coffee', 'hi', 'CREATED', 'PENDING', 'ACTIVE', 'CLEAR', 'PENDING',"
                        + " 'PRIVATE', false, false, 0, 0, 0, 1, now())",
                videoId,
                creatorId);
    }

    private List<String> queueIds() {
        return moderation.listPending(null, 1000).items().stream()
                .map(v -> v.videoId())
                .toList();
    }

    private Long version(String videoId) {
        return jdbc.queryForObject(
                "SELECT aggregate_version FROM moderation.moderation_record WHERE video_id = ?::uuid", Long.class, videoId);
    }

    @Test
    void anUntrustedUploadIsHeldOutOfTheQueueUntilItsOwnerAsksToPublish() {
        String video = videoId();
        String creator = UUID.randomUUID().toString();
        withMetadata(video, creator);

        moderation.createPending(video, creator);

        assertThat(queueIds()).doesNotContain(video);
        Long before = version(video);

        assertThat(moderation.requestReview(video)).isTrue();

        assertThat(queueIds()).contains(video);
        assertThat(jdbc.queryForObject(
                        "SELECT queue_reason FROM moderation.moderation_record WHERE video_id = ?::uuid",
                        String.class,
                        video))
                .isEqualTo("PUBLISH_REQUESTED");
        // Queueing is not a business state change and emits no event, so the version must not move.
        assertThat(version(video)).isEqualTo(before);
    }

    @Test
    void anUploadWithNoMetadataIsQueuedAtOnce() {
        String video = videoId();

        moderation.createPending(video, UUID.randomUUID().toString());

        assertThat(queueIds()).contains(video);
        assertThat(jdbc.queryForObject(
                        "SELECT queue_reason FROM moderation.moderation_record WHERE video_id = ?::uuid",
                        String.class,
                        video))
                .isEqualTo("UNSCREENED");
    }

    @Test
    void requestingReviewTwiceChangesNothingTheSecondTime() {
        String video = videoId();
        String creator = UUID.randomUUID().toString();
        withMetadata(video, creator);
        moderation.createPending(video, creator);

        assertThat(moderation.requestReview(video)).isTrue();
        Object queuedAt = jdbc.queryForObject(
                "SELECT queued_at FROM moderation.moderation_record WHERE video_id = ?::uuid", Object.class, video);

        assertThat(moderation.requestReview(video)).isFalse();
        assertThat(jdbc.queryForObject(
                        "SELECT queued_at FROM moderation.moderation_record WHERE video_id = ?::uuid",
                        Object.class,
                        video))
                .isEqualTo(queuedAt);
    }

    @Test
    void aDecidedRecordIsNotQueuedByALatePublishRequest() {
        String video = videoId();
        String creator = UUID.randomUUID().toString();
        withMetadata(video, creator);
        moderation.createPending(video, creator);
        jdbc.update("UPDATE moderation.moderation_record SET state = 'APPROVED' WHERE video_id = ?::uuid", video);

        assertThat(moderation.requestReview(video)).isFalse();

        assertThat(jdbc.queryForObject(
                        "SELECT queued_at FROM moderation.moderation_record WHERE video_id = ?::uuid",
                        Object.class,
                        video))
                .isNull();
    }

    @Test
    void aPublishRequestThatOvertakesTheRecordFailsSoItIsRetried() {
        assertThatThrownBy(() -> moderation.requestReview(videoId())).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void heldRecordsAreListedForTheSweepAndQueuedOnesAreNot() {
        String held = videoId();
        String creator = UUID.randomUUID().toString();
        withMetadata(held, creator);
        moderation.createPending(held, creator);
        String queued = videoId();
        moderation.createPending(queued, UUID.randomUUID().toString()); // no metadata: queued

        List<String> heldIds = moderation.findHeldPage(null, 1000);

        assertThat(heldIds).contains(held).doesNotContain(queued);
    }

    @Test
    void theQueueIsOrderedByWhenEachItemStartedWaiting() {
        String first = videoId();
        String second = videoId();
        String creator = UUID.randomUUID().toString();
        withMetadata(first, creator);
        withMetadata(second, creator);
        moderation.createPending(first, creator);
        moderation.createPending(second, creator);
        // The later upload asks to publish first: it has been waiting for a reviewer the longest.
        moderation.requestReview(second);
        moderation.requestReview(first);

        List<String> ids = queueIds();

        assertThat(ids.indexOf(second)).isLessThan(ids.indexOf(first));
    }

    @Test
    void aRemovedVideoLeavesTheQueueAndCannotBeQueuedAgain() {
        String video = videoId();
        moderation.createPending(video, UUID.randomUUID().toString()); // no metadata: queued
        assertThat(queueIds()).contains(video);
        Long before = version(video);

        moderation.closeForRemovedVideo(video);

        assertThat(queueIds()).doesNotContain(video);
        assertThat(moderation.findHeldPage(null, 1000)).doesNotContain(video);
        assertThat(moderation.requestReview(video)).isFalse();
        // Closing is not a decision: no state change, so the version does not move.
        assertThat(version(video)).isEqualTo(before);
    }

    @Test
    void aHeldVideoThatIsRemovedIsNotSweptAgain() {
        String video = videoId();
        String creator = UUID.randomUUID().toString();
        withMetadata(video, creator);
        moderation.createPending(video, creator);
        assertThat(moderation.findHeldPage(null, 1000)).contains(video);

        moderation.closeForRemovedVideo(video);

        assertThat(moderation.findHeldPage(null, 1000)).doesNotContain(video);
    }

    @Test
    void closingADecidedOrUnknownVideoChangesNothing() {
        String video = videoId();
        moderation.createPending(video, UUID.randomUUID().toString());
        jdbc.update("UPDATE moderation.moderation_record SET state = 'APPROVED' WHERE video_id = ?::uuid", video);

        moderation.closeForRemovedVideo(video);
        moderation.closeForRemovedVideo(videoId()); // no record at all

        assertThat(jdbc.queryForObject(
                        "SELECT closed_at FROM moderation.moderation_record WHERE video_id = ?::uuid",
                        Object.class,
                        video))
                .isNull();
    }

    /** A creator the screener trusts: enough approved videos, none rejected. */
    private String trustedCreator() {
        String creator = UUID.randomUUID().toString();
        for (int i = 0; i < 5; i++) {
            jdbc.update(
                    "INSERT INTO moderation.moderation_record (video_id, creator_id, state, aggregate_version, created_at, updated_at)"
                            + " VALUES (?::uuid, ?::uuid, 'APPROVED', 1, now(), now())",
                    UUID.randomUUID().toString(),
                    creator);
        }
        return creator;
    }

    private int outboxEvents(String video, String type) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM platform.outbox_event WHERE aggregate_id = ? AND event_type = ?",
                Integer.class,
                video,
                type);
    }

    @Test
    void aTrustedCreatorsCleanUploadIsApprovedByTheSystemEndToEnd() {
        String video = videoId();
        String creator = trustedCreator();
        withMetadata(video, creator);

        assertThat(moderation.createPending(video, creator)).isTrue();

        assertThat(jdbc.queryForObject(
                        "SELECT state FROM moderation.moderation_record WHERE video_id = ?::uuid", String.class, video))
                .isEqualTo("APPROVED");
        // Never entered the queue, and nothing is waiting on a person.
        assertThat(queueIds()).doesNotContain(video);
        assertThat(outboxEvents(video, "video.moderation.approved")).isEqualTo(1);
        // The audit trail says the system did it, not a person.
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM platform.admin_action WHERE target_id = ? AND action = 'video.approved'"
                                + " AND actor_account_id = ?::uuid",
                        Integer.class,
                        video,
                        ModerationService.SYSTEM_ACTOR))
                .isEqualTo(1);
    }

    /** upload.completed is delivered at least once; the second delivery must not approve (or announce) again. */
    @Test
    void aRedeliveredUploadCompletionDoesNotApproveTwice() {
        String video = videoId();
        String creator = trustedCreator();
        withMetadata(video, creator);
        moderation.createPending(video, creator);

        assertThat(moderation.createPending(video, creator)).isFalse();

        assertThat(outboxEvents(video, "video.moderation.approved")).isEqualTo(1);
    }

    @Test
    void aTrustedCreatorWhoHasBeenRejectedBeforeIsQueuedNotApproved() {
        String video = videoId();
        String creator = trustedCreator();
        jdbc.update(
                "INSERT INTO moderation.moderation_record (video_id, creator_id, state, aggregate_version, created_at, updated_at)"
                        + " VALUES (?::uuid, ?::uuid, 'REJECTED', 1, now(), now())",
                UUID.randomUUID().toString(),
                creator);
        withMetadata(video, creator);

        moderation.createPending(video, creator);

        assertThat(queueIds()).contains(video);
        assertThat(outboxEvents(video, "video.moderation.approved")).isZero();
    }
}

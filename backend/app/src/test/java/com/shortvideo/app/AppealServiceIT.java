package com.shortvideo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shortvideo.appeal.api.AppealState;
import com.shortvideo.appeal.domain.AppealExceptions;
import com.shortvideo.appeal.domain.AppealService;
import com.shortvideo.appeal.domain.AppealView;
import com.shortvideo.moderation.api.PolicyCategory;
import com.shortvideo.moderation.domain.ModerationService;
import com.shortvideo.shared.events.EventTypes;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Submitting an appeal against real PostgreSQL: who may, when, and what happens when the same
 * creator submits twice at once or appeals a video that was reinstated and then rejected again.
 */
class AppealServiceIT extends AbstractDatabaseIT {

    @Autowired AppealService appeals;
    @Autowired ModerationService moderation;
    @Autowired JdbcTemplate jdbc;

    private static final String ADMIN = ModerationService.SYSTEM_ACTOR;

    /** A READY video owned by {@code owner}, moderation still pending. */
    private String video(String owner) {
        String video = UUID.randomUUID().toString();
        jdbc.update(
                "INSERT INTO video.video (video_id, owner_account_id, processing_state, processing_version,"
                        + " durability_state, asset_lifecycle_state, legal_serving_state, created_at, updated_at)"
                        + " VALUES (?::uuid, ?::uuid, 'READY', 1, 'DURABLE', 'ACTIVE', 'CLEAR', now(), now())",
                video,
                owner);
        moderation.createPending(video, owner);
        return video;
    }

    private String rejectedVideo(String owner) {
        String video = video(owner);
        reject(video);
        return video;
    }

    private void reject(String video) {
        // A different reason each time: the same rejection twice is a repeat and changes nothing.
        moderation.reject(video, PolicyCategory.SPAM_DECEPTIVE, UUID.randomUUID().toString(), ADMIN);
    }

    private static String account() {
        return UUID.randomUUID().toString();
    }

    private int outboxEvents(String video, String type) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM platform.outbox_event WHERE aggregate_id = ? AND event_type = ?",
                Integer.class,
                video,
                type);
    }

    /** The moderation version of the rejection the video is under now. */
    private long rejectionVersion(String video) {
        return moderation.findDecision(video).orElseThrow().aggregateVersion();
    }

    /** The version an admin reading the appeal now would send back with a decision. */
    private long versionOf(String video, String owner) {
        return appeals.getStatus(video, owner).version();
    }

    @Test
    void theOwnerOfARejectedVideoCanAppealIt() {
        String owner = account();
        String video = rejectedVideo(owner);

        var view = appeals.submit(video, owner, "this was a mistake");

        assertThat(view.state()).isEqualTo(AppealState.UNDER_APPEAL);
        assertThat(appeals.getStatus(video, owner).state()).isEqualTo(AppealState.UNDER_APPEAL);
    }

    @Test
    void someoneElsesVideoAndAMissingVideoAreIndistinguishable() {
        String video = rejectedVideo(account());

        assertThatThrownBy(() -> appeals.submit(video, account(), "not mine"))
                .isInstanceOf(AppealExceptions.AppealNotFound.class);
        assertThatThrownBy(() -> appeals.submit(UUID.randomUUID().toString(), account(), "no such video"))
                .isInstanceOf(AppealExceptions.AppealNotFound.class);
    }

    @Test
    void aVideoThatIsNotRejectedCannotBeAppealed() {
        String owner = account();
        String video = video(owner);

        assertThatThrownBy(() -> appeals.submit(video, owner, "too early"))
                .isInstanceOf(AppealExceptions.NotEligibleForAppeal.class);
    }

    @Test
    void anAppealAlreadyUnderReviewCannotBeSubmittedAgain() {
        String owner = account();
        String video = rejectedVideo(owner);
        appeals.submit(video, owner, "first");

        assertThatThrownBy(() -> appeals.submit(video, owner, "second"))
                .isInstanceOf(AppealExceptions.NotEligibleForAppeal.class);
    }

    @Test
    void anAdminCannotDecideAnAppealOfTheirOwnVideo() {
        String owner = account();
        String video = rejectedVideo(owner);
        appeals.submit(video, owner, "mine");

        assertThatThrownBy(() -> appeals.approve(video, "fine by me", versionOf(video, owner), owner))
                .isInstanceOf(AppealExceptions.OwnAppeal.class);
        assertThatThrownBy(() -> appeals.deny(video, "no", versionOf(video, owner), owner))
                .isInstanceOf(AppealExceptions.OwnAppeal.class);
        assertThat(appeals.getStatus(video, owner).state()).isEqualTo(AppealState.UNDER_APPEAL);
    }

    @Test
    void aDecisionMadeOnAVersionThatIsNoLongerCurrentIsRefused() {
        String owner = account();
        String video = rejectedVideo(owner);
        appeals.submit(video, owner, "first");
        long seen = versionOf(video, owner);

        // The creator's appeal is withdrawn from under the admin and filed again with a new rationale.
        jdbc.update("UPDATE appeal.appeal SET reason = 'rewritten', aggregate_version = aggregate_version + 1"
                + " WHERE video_id = ?::uuid", video);

        assertThatThrownBy(() -> appeals.approve(video, "ok", seen, ADMIN))
                .isInstanceOf(AppealExceptions.AppealChanged.class);
        assertThatThrownBy(() -> appeals.deny(video, "no", seen, ADMIN))
                .isInstanceOf(AppealExceptions.AppealChanged.class);
        assertThat(appeals.getStatus(video, owner).state()).isEqualTo(AppealState.UNDER_APPEAL);

        assertThat(appeals.approve(video, "ok", versionOf(video, owner), ADMIN).state())
                .isEqualTo(AppealState.APPROVED);
    }

    @Test
    void anAppealAlreadyDecidedIsNotAwaitingADecisionEvenOnItsCurrentVersion() {
        String owner = account();
        String video = rejectedVideo(owner);
        appeals.submit(video, owner, "first");
        appeals.deny(video, "no", versionOf(video, owner), ADMIN);

        assertThatThrownBy(() -> appeals.approve(video, "changed my mind", versionOf(video, owner), ADMIN))
                .isInstanceOf(AppealExceptions.AppealNotPending.class);
    }

    @Test
    void decidingTheSameAppealTwiceIsRefusedAndPublishesOneEvent() {
        String owner = account();
        String video = rejectedVideo(owner);
        appeals.submit(video, owner, "first");
        long seen = versionOf(video, owner);
        appeals.approve(video, "fair", seen, ADMIN);

        // A retry after a lost response, with the very version the first call used.
        assertThatThrownBy(() -> appeals.approve(video, "fair", seen, ADMIN))
                .isInstanceOf(AppealExceptions.AppealNotPending.class);
        assertThatThrownBy(() -> appeals.deny(video, "no", seen, ADMIN))
                .isInstanceOf(AppealExceptions.AppealNotPending.class);

        assertThat(outboxEvents(video, EventTypes.VIDEO_APPEAL_APPROVED)).isEqualTo(1);
        assertThat(outboxEvents(video, EventTypes.VIDEO_APPEAL_DENIED)).isZero();
    }

    /** Two admins decide the same appeal from the same version: one decision stands, the other is refused. */
    @Test
    void anApproveAndADenyAtOnceLeaveExactlyOneDecision() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 30; round++) {
                String owner = account();
                String video = rejectedVideo(owner);
                appeals.submit(video, owner, "please");
                long seen = versionOf(video, owner);
                CyclicBarrier start = new CyclicBarrier(2);
                Future<Object> approve = pool.submit(() -> {
                    start.await();
                    return appeals.approve(video, "fair", seen, ADMIN);
                });
                Future<Object> deny = pool.submit(() -> {
                    start.await();
                    return appeals.deny(video, "no", seen, ADMIN);
                });

                int decided = 0;
                for (Future<Object> call : List.of(approve, deny)) {
                    try {
                        call.get();
                        decided++;
                    } catch (java.util.concurrent.ExecutionException e) {
                        assertThat(e.getCause())
                                .isInstanceOfAny(
                                        AppealExceptions.AppealNotPending.class,
                                        AppealExceptions.AppealChanged.class,
                                        org.springframework.dao.OptimisticLockingFailureException.class);
                    }
                }
                assertThat(decided).isEqualTo(1);
                assertThat(outboxEvents(video, EventTypes.VIDEO_APPEAL_APPROVED)
                                + outboxEvents(video, EventTypes.VIDEO_APPEAL_DENIED))
                        .isEqualTo(1);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void thePendingListIsOldestFirstAndHonoursItsLimit() {
        List<String> inOrder = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            String owner = account();
            String video = rejectedVideo(owner);
            appeals.submit(video, owner, "appeal " + i);
            // Far older than anything another test leaves pending, and 3 > 2 > 1 years ago in this order.
            jdbc.update(
                    "UPDATE appeal.appeal SET updated_at = now() - (?::int * interval '1 year') - interval '100 years'"
                            + " WHERE video_id = ?::uuid",
                    3 - i,
                    video);
            inOrder.add(video);
        }

        try {
            assertThat(appeals.listPending(3)).extracting(AppealView::videoId).containsExactlyElementsOf(inOrder);
            assertThat(appeals.listPending(2))
                    .extracting(AppealView::videoId)
                    .containsExactlyElementsOf(inOrder.subList(0, 2));
            assertThat(appeals.listPending(0)).hasSize(1);
            assertThat(appeals.listPending(Integer.MAX_VALUE)).hasSizeLessThanOrEqualTo(AppealService.MAX_PENDING_PAGE);
        } finally {
            // They stay PENDING and a century old. Left behind, they would head the list on the next run
            // against the same database and push that run's own three out of the first three.
            for (String video : inOrder) {
                jdbc.update("DELETE FROM appeal.appeal WHERE video_id = ?::uuid", video);
            }
        }
    }

    /** Moves the denial back in time, as if it had been decided that long ago. */
    private void deniedAgo(String video, String age) {
        jdbc.update(
                "UPDATE appeal.appeal SET updated_at = now() - ?::interval WHERE video_id = ?::uuid", age, video);
    }

    @Test
    void aDeniedAppealCannotBeSubmittedAgainUntilItsCooldownHasPassed() {
        String owner = account();
        String video = rejectedVideo(owner);
        appeals.submit(video, owner, "first");
        appeals.deny(video, "no", versionOf(video, owner), ADMIN);

        assertThatThrownBy(() -> appeals.submit(video, owner, "right away"))
                .isInstanceOfSatisfying(AppealExceptions.ResubmitTooSoon.class, e -> assertThat(e.retryAfter())
                        .isPositive()
                        .isLessThanOrEqualTo(java.time.Duration.ofHours(24)));
        assertThat(appeals.getStatus(video, owner).state()).isEqualTo(AppealState.DENIED);

        deniedAgo(video, "23 hours");
        assertThatThrownBy(() -> appeals.submit(video, owner, "still early"))
                .isInstanceOf(AppealExceptions.ResubmitTooSoon.class);

        deniedAgo(video, "25 hours");
        assertThat(appeals.submit(video, owner, "after the wait").state()).isEqualTo(AppealState.UNDER_APPEAL);
    }

    @Test
    void aDeniedAppealCanBeSubmittedAgainOnceTheCooldownHasPassed() {
        String owner = account();
        String video = rejectedVideo(owner);
        appeals.submit(video, owner, "first");
        appeals.deny(video, "no", versionOf(video, owner), ADMIN);
        deniedAgo(video, "2 days");

        var view = appeals.submit(video, owner, "second try");

        assertThat(view.state()).isEqualTo(AppealState.UNDER_APPEAL);
        assertThat(view.reason()).isEqualTo("second try");
        assertThat(view.decisionReason()).isNull();
    }

    /**
     * Approval reinstates the video, so a later rejection is a new case. The decided appeal must not
     * lock the creator out of appealing it with a message about a review that is not happening.
     */
    @Test
    void aVideoRejectedAgainAfterAnApprovedAppealCanBeAppealedAgain() {
        String owner = account();
        String video = rejectedVideo(owner);
        appeals.submit(video, owner, "first");
        appeals.approve(video, "fair", versionOf(video, owner), ADMIN);
        moderation.reinstateFromAppeal(video, rejectionVersion(video));
        reject(video);

        var view = appeals.submit(video, owner, "rejected again");

        assertThat(view.state()).isEqualTo(AppealState.UNDER_APPEAL);
        assertThat(view.reason()).isEqualTo("rejected again");
    }

    @Test
    void anAppealCannotBeApprovedOnceTheVideoWasReinstatedAndRejectedAgain() {
        String owner = account();
        String video = rejectedVideo(owner);
        appeals.submit(video, owner, "first");
        moderation.approve(video, ADMIN);
        reject(video);

        assertThatThrownBy(() -> appeals.approve(video, "fair", versionOf(video, owner), ADMIN))
                .isInstanceOf(AppealExceptions.RejectionChanged.class);

        assertThat(appeals.getStatus(video, owner).state()).isEqualTo(AppealState.UNDER_APPEAL);
        assertThat(outboxEvents(video, EventTypes.VIDEO_APPEAL_APPROVED)).isZero();
        // It can still be turned down, which is how an admin clears a case that no longer applies.
        assertThat(appeals.deny(video, "superseded", versionOf(video, owner), ADMIN).state())
                .isEqualTo(AppealState.DENIED);
    }

    @Test
    void anAppealCannotBeApprovedOnceTheRejectionWasReclassified() {
        String owner = account();
        String video = rejectedVideo(owner);
        appeals.submit(video, owner, "first");
        reject(video);

        assertThatThrownBy(() -> appeals.approve(video, "fair", versionOf(video, owner), ADMIN))
                .isInstanceOf(AppealExceptions.RejectionChanged.class);
    }

    /** An approval delivered late must reverse the rejection it was decided against, not a later one. */
    @Test
    void anApprovalDeliveredAfterTheVideoWasRejectedAgainReinstatesNothing() {
        String owner = account();
        String video = rejectedVideo(owner);
        long appealed = rejectionVersion(video);
        moderation.approve(video, ADMIN);
        reject(video);

        assertThat(moderation.reinstateFromAppeal(video, appealed)).isFalse();

        assertThat(moderation.findDecision(video).orElseThrow().state()).isEqualTo("REJECTED");
    }

    @Test
    void anApprovalAtTheRejectionItNamesReinstatesTheVideoOnce() {
        String owner = account();
        String video = rejectedVideo(owner);
        appeals.submit(video, owner, "first");
        appeals.approve(video, "fair", versionOf(video, owner), ADMIN);
        long appealed = rejectionVersion(video);

        assertThat(moderation.reinstateFromAppeal(video, appealed)).isTrue();
        assertThat(moderation.reinstateFromAppeal(video, appealed)).isFalse();

        assertThat(moderation.findDecision(video).orElseThrow().state()).isEqualTo("REINSTATED");
    }

    @Test
    void theSweepFindsAnApprovedAppealWithTheRejectionItWasFiledAgainst() {
        String owner = account();
        String video = rejectedVideo(owner);
        long appealed = rejectionVersion(video);
        appeals.submit(video, owner, "first");
        appeals.approve(video, "fair", versionOf(video, owner), ADMIN);
        java.time.Instant now = java.time.Instant.now();

        var found = appeals.findApprovedPage(now.minusSeconds(3600), now.plusSeconds(3600), null, 1000);

        assertThat(found).anySatisfy(a -> {
            assertThat(a.videoId()).isEqualTo(video);
            assertThat(a.moderationVersion()).isEqualTo(appealed);
            assertThat(a.decidedAt()).isBetween(now.minusSeconds(3600), now.plusSeconds(1));
        });
        assertThat(appeals.findApprovedPage(now.plusSeconds(60), now.plusSeconds(3600), null, 1000))
                .extracting(com.shortvideo.appeal.api.AppealDirectory.ApprovedAppealView::videoId)
                .doesNotContain(video);
    }

    /** The cursor is the last row's (decision time, video id): a page boundary must neither repeat nor skip one. */
    @Test
    void theSweepPagesThroughApprovedAppealsInDecisionOrderWithoutRepeatingOrSkipping() {
        java.time.Instant from = java.time.Instant.now().minusSeconds(1);
        List<String> approved = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            String owner = account();
            String video = rejectedVideo(owner);
            appeals.submit(video, owner, "first");
            appeals.approve(video, "fair", versionOf(video, owner), ADMIN);
            approved.add(video);
        }
        java.time.Instant to = java.time.Instant.now().plusSeconds(3600);

        List<String> seen = new ArrayList<>();
        com.shortvideo.appeal.api.AppealDirectory.ApprovedAppealView cursor = null;
        while (true) {
            var page = appeals.findApprovedPage(from, to, cursor, 1);
            if (page.isEmpty()) {
                break;
            }
            cursor = page.get(page.size() - 1);
            seen.add(cursor.videoId());
        }

        assertThat(seen).doesNotHaveDuplicates().containsAll(approved);
        assertThat(approved.stream().map(seen::indexOf).toList()).isSorted();
    }

    /** Both requests find no row and insert one; the loser must be told 409, not hit the catch-all. */
    @Test
    void twoFirstAppealsAtOnceAdmitOneAndRefuseTheOtherAsIneligible() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 30; round++) {
                String owner = account();
                String video = rejectedVideo(owner);
                CyclicBarrier start = new CyclicBarrier(2);
                List<Future<Object>> calls = new ArrayList<>();
                for (int i = 0; i < 2; i++) {
                    String reason = "attempt " + i;
                    calls.add(pool.submit(() -> {
                        start.await();
                        return appeals.submit(video, owner, reason);
                    }));
                }

                int admitted = 0;
                for (Future<Object> call : calls) {
                    try {
                        call.get();
                        admitted++;
                    } catch (java.util.concurrent.ExecutionException e) {
                        assertThat(e.getCause()).isInstanceOf(AppealExceptions.NotEligibleForAppeal.class);
                    }
                }
                assertThat(admitted).isEqualTo(1);
                assertThat(appeals.getStatus(video, owner).state()).isEqualTo(AppealState.UNDER_APPEAL);
            }
        } finally {
            pool.shutdownNow();
        }
    }
}

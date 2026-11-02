package com.shortvideo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shortvideo.appeal.api.AppealState;
import com.shortvideo.appeal.domain.AppealExceptions;
import com.shortvideo.appeal.domain.AppealService;
import com.shortvideo.moderation.api.PolicyCategory;
import com.shortvideo.moderation.domain.ModerationService;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Submitting an appeal against real PostgreSQL: who may, when, and what happens when the same
 * creator submits twice at once or appeals a video that was reinstated and then rejected again.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AppealSubmitIT {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        TestDatabase.register(registry);
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:1");
    }

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
        appeals.deny(video, "no", ADMIN);

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
        appeals.deny(video, "no", ADMIN);
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
        appeals.approve(video, "fair", ADMIN);
        moderation.reinstateFromAppeal(video);
        reject(video);

        var view = appeals.submit(video, owner, "rejected again");

        assertThat(view.state()).isEqualTo(AppealState.UNDER_APPEAL);
        assertThat(view.reason()).isEqualTo("rejected again");
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

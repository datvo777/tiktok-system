package com.shortvideo.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.shortvideo.eligibility.api.EligibilityDirectory;
import com.shortvideo.eligibility.api.VideoEligibilityView;
import com.shortvideo.social.api.SocialDirectory;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The two queries behind the Following feed's candidate set, against real
 * PostgreSQL: the follow list, and eligible videos restricted to given creators.
 * The array binding and the eligibility join are what a mock cannot vouch for.
 */
class FollowingCandidatesIT extends AbstractDatabaseIT {

    @Autowired EligibilityDirectory eligibility;
    @Autowired SocialDirectory social;
    @Autowired JdbcTemplate jdbc;

    @Test
    void anOldVideoOfAFollowedCreatorIsFoundEvenWhenNewerOnesExistElsewhere() {
        String followed = account(true);
        String other = account(true);
        String old = video(followed, true, "30 days");
        for (int i = 0; i < 5; i++) {
            video(other, true, "1 minute");
        }

        List<String> ids = eligibility.findEligibleVideosByCreators(Set.of(followed), 10).stream()
                .map(VideoEligibilityView::videoId)
                .toList();

        // The newest-N pool would not hold it at a small N; this query does not depend on N.
        assertThat(ids).containsExactly(old);
        assertThat(eligibility.findEligibleVideosWithEligibleCreators(5))
                .extracting(VideoEligibilityView::videoId)
                .doesNotContain(old);
    }

    @Test
    void ineligibleVideosAndIneligibleCreatorsAreStillExcluded() {
        String good = account(true);
        String suspended = account(false);
        String eligible = video(good, true, "1 hour");
        video(good, false, "1 hour");
        video(suspended, true, "1 hour");

        List<String> ids = eligibility.findEligibleVideosByCreators(Set.of(good, suspended), 10).stream()
                .map(VideoEligibilityView::videoId)
                .toList();

        assertThat(ids).containsExactly(eligible);
        assertThat(eligibility.findEligibleVideosByCreators(Set.of(), 10)).isEmpty();
    }

    @Test
    void followedCreatorsComeBackNewestFirstAndAreBounded() {
        String viewer = UUID.randomUUID().toString();
        String first = follow(viewer, "3 days");
        String second = follow(viewer, "2 days");
        String third = follow(viewer, "1 day");

        assertThat(social.followedCreators(viewer, 10)).containsExactly(third, second, first);
        assertThat(social.followedCreators(viewer, 2)).containsExactly(third, second);
        assertThat(social.followedCreators(UUID.randomUUID().toString(), 10)).isEmpty();
    }

    private String account(boolean eligible) {
        String id = UUID.randomUUID().toString();
        jdbc.update(
                "INSERT INTO eligibility.account_eligibility (account_id, account_state, is_account_eligible,"
                        + " source_version, updated_at) VALUES (?, ?, ?, 1, now())",
                id,
                eligible ? "ACTIVE" : "SUSPENDED",
                eligible);
        return id;
    }

    private String video(String creatorId, boolean eligible, String age) {
        String id = UUID.randomUUID().toString();
        jdbc.update(
                "INSERT INTO eligibility.video_eligibility (video_id, creator_id, processing_state, durability_state,"
                        + " asset_lifecycle_state, legal_serving_state, moderation_state, publication_state,"
                        + " publication_intent_requested, is_video_eligible, processing_version_source,"
                        + " moderation_version_source, publication_version_source, updated_at)"
                        + " VALUES (?, ?, 'READY', 'DURABLE', 'ACTIVE', 'CLEAR', 'APPROVED', 'PUBLISHED', true, ?,"
                        + " 1, 1, 1, now() - ?::interval)",
                id,
                creatorId,
                eligible,
                age);
        return id;
    }

    private String follow(String followerId, String age) {
        String followee = UUID.randomUUID().toString();
        jdbc.update(
                "INSERT INTO social.follow (follower_id, followee_id, created_at)"
                        + " VALUES (?::uuid, ?::uuid, now() - ?::interval)",
                followerId,
                followee,
                age);
        return followee;
    }
}

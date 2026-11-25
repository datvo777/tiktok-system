package com.shortvideo.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.shortvideo.eligibility.api.EligibilityCorrector;
import com.shortvideo.eligibility.api.EligibilityDirectory;
import com.shortvideo.eligibility.api.VideoEligibilityView;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * A video's age for the feed is when it first became eligible, not when its row was last written.
 * Publishing again, or any other write, must not make an old video look new, and must not move it
 * ahead of a newer one in the candidate pool.
 */
class FirstEligibleRankingIT extends AbstractDatabaseIT {

    @Autowired EligibilityCorrector corrector;
    @Autowired EligibilityDirectory directory;

    private String creator;

    private void creatorIsActive() {
        creator = UUID.randomUUID().toString();
        corrector.correctAccount(creator, "ACTIVE", 1);
    }

    private String publishedVideo() {
        String video = UUID.randomUUID().toString();
        corrector.correctVideoProcessing(video, creator, "READY", 1, "DURABLE", "ACTIVE", "CLEAR", 1);
        corrector.correctModeration(video, creator, "APPROVED", 1);
        corrector.correctPublication(video, creator, "PUBLISHED", true, 1);
        return video;
    }

    private VideoEligibilityView view(String video) {
        return directory.findVideoEligibility(video).orElseThrow();
    }

    private static void pause() throws InterruptedException {
        Thread.sleep(25); // timestamps are written at millisecond resolution or better
    }

    @Test
    void aVideoIsStampedTheFirstTimeItBecomesEligibleAndNotBefore() {
        creatorIsActive();
        String video = UUID.randomUUID().toString();
        corrector.correctVideoProcessing(video, creator, "READY", 1, "DURABLE", "ACTIVE", "CLEAR", 1);
        corrector.correctModeration(video, creator, "APPROVED", 1);

        assertThat(view(video).isVideoEligible()).isFalse();
        assertThat(view(video).firstEligibleAt()).isNull();

        corrector.correctPublication(video, creator, "PUBLISHED", true, 1);

        assertThat(view(video).isVideoEligible()).isTrue();
        assertThat(view(video).firstEligibleAt()).isNotNull();
    }

    @Test
    void publishingAgainDoesNotMakeAnOldVideoNewAgain() throws Exception {
        creatorIsActive();
        String video = publishedVideo();
        var first = view(video);
        pause();

        corrector.correctPublication(video, creator, "PRIVATE", false, 2);
        pause();
        corrector.correctPublication(video, creator, "PUBLISHED", true, 3);

        var again = view(video);
        assertThat(again.isVideoEligible()).isTrue();
        assertThat(again.updatedAt()).isAfter(first.updatedAt());
        assertThat(again.firstEligibleAt()).isEqualTo(first.firstEligibleAt());
        assertThat(again.rankedSince()).isEqualTo(first.firstEligibleAt());
    }

    @Test
    void aVideoWithdrawnLaterKeepsItsStamp() {
        creatorIsActive();
        String video = publishedVideo();
        var first = view(video);

        corrector.correctPublication(video, creator, "PRIVATE", false, 2);

        assertThat(view(video).isVideoEligible()).isFalse();
        assertThat(view(video).firstEligibleAt()).isEqualTo(first.firstEligibleAt());
    }

    @Test
    void theCandidatePoolIsOrderedByFirstEligibilityNotByTheLastWrite() throws Exception {
        creatorIsActive();
        String older = publishedVideo();
        pause();
        String newer = publishedVideo();
        pause();
        // The older video is written last, which used to put it first in the pool.
        corrector.correctPublication(older, creator, "PRIVATE", false, 2);
        pause();
        corrector.correctPublication(older, creator, "PUBLISHED", true, 3);

        assertThat(view(older).updatedAt()).isAfter(view(newer).updatedAt());
        List<String> pool = directory.findEligibleVideosWithEligibleCreators(1_000_000).stream()
                .map(VideoEligibilityView::videoId)
                .toList();
        assertThat(pool).containsSubsequence(newer, older);

        List<String> following = directory.findEligibleVideosByCreators(List.of(creator), 100).stream()
                .map(VideoEligibilityView::videoId)
                .toList();
        assertThat(following).containsExactly(newer, older);
    }
}

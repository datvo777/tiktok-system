package com.shortvideo.feed.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The ordering rule the feed sorts by.
 *
 * <p>"Already seen" started life as a score penalty and this is the test that
 * showed why that could not work: with a like weight of 1, a video with twenty
 * thousand points of engagement outruns any fixed penalty someone picks, so a
 * viewer's most-engaging watched video kept coming back to the top. Seen is a
 * partition, not a weight — and this asserts that it cannot be out-scored.
 */
class FeedRankingOrderTest {

    /** Mirrors the comparator in {@code FeedService.rank}. */
    private static final Comparator<Candidate> ORDER = Comparator.comparing(Candidate::seen)
            .thenComparing(Comparator.comparingDouble(Candidate::score).reversed())
            .thenComparing(Candidate::id);

    private record Candidate(String id, double score, boolean seen) {}

    @Test
    void everyUnseenVideoOutranksEverySeenOne() {
        List<Candidate> candidates = new ArrayList<>(List.of(
                new Candidate("seen-huge", 20_000, true),
                new Candidate("unseen-tiny", 0.001, false),
                new Candidate("seen-small", 1, true),
                new Candidate("unseen-big", 500, false)));

        candidates.sort(ORDER);

        assertThat(candidates).extracting(Candidate::id)
                .containsExactly("unseen-big", "unseen-tiny", "seen-huge", "seen-small");
    }

    @Test
    void withinATierTheHigherScoreWins() {
        List<Candidate> candidates =
                new ArrayList<>(List.of(new Candidate("low", 1, false), new Candidate("high", 2, false)));

        candidates.sort(ORDER);

        assertThat(candidates).extracting(Candidate::id).containsExactly("high", "low");
    }

    /**
     * Ties must break deterministically, or two computations of the same ranking
     * disagree — which is how a video ended up on both page 0 and page 1, or on
     * neither.
     */
    @Test
    void tiesBreakOnIdSoTheOrderingIsStable() {
        List<Candidate> first =
                new ArrayList<>(List.of(new Candidate("b", 5, false), new Candidate("a", 5, false)));
        List<Candidate> second =
                new ArrayList<>(List.of(new Candidate("a", 5, false), new Candidate("b", 5, false)));

        first.sort(ORDER);
        second.sort(ORDER);

        assertThat(first).isEqualTo(second);
        assertThat(first).extracting(Candidate::id).containsExactly("a", "b");
    }
}

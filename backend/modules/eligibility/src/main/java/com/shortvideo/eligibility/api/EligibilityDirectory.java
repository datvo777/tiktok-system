package com.shortvideo.eligibility.api;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Read-only access to the durable eligibility projections (brief section 17).
 * Callers evaluate an explicit allow by joining both rows on {@code creatorId}; a
 * missing row is unknown state and denies (Rule 9). Media authorization always
 * reads this — never a Redis-cached copy (Rule 12).
 */
public interface EligibilityDirectory {

    Optional<VideoEligibilityView> findVideoEligibility(String videoId);

    Optional<AccountEligibilityView> findAccountEligibility(String accountId);

    /**
     * As {@link #findVideoEligibility}, for a whole set of ids in one query.
     * Videos with no projection row are simply absent from the result, so
     * unknown still denies (Rule 9) — a caller iterating the result sees only
     * videos this projection actually knows about.
     *
     * <p>Exists for callers holding an arbitrary list of video ids (a favorite
     * collection's items) rather than a ranked candidate set: one round trip
     * instead of one per id.
     */
    List<VideoEligibilityView> findVideoEligibilities(Collection<String> videoIds);

    /**
     * Candidate source for the Feed module (brief section 15): currently eligible
     * videos, most recently eligible first. This is a ranking input, not an
     * authorization decision — the gateway never uses this method (Rule 12).
     */
    List<VideoEligibilityView> findEligibleVideos(int limit);

    /**
     * As {@link #findEligibleVideos}, but returning only videos whose creator is
     * also eligible — the join is done in PostgreSQL instead of one
     * {@code findAccountEligibility} call per candidate.
     *
     * <p>Both projections live in this module's own schema, so this stays a
     * single-module query and does not reach across a module boundary. Rule 9 is
     * preserved: a video whose creator has no projection row is absent from the
     * result rather than present-and-unfiltered, so unknown still denies.
     */
    List<VideoEligibilityView> findEligibleVideosWithEligibleCreators(int limit);

    /** Every video this projection currently tracks, eligible or not (brief section 20, Milestone 5). */
    List<String> allTrackedVideoIds(int limit);

    /** Every account this projection currently tracks (brief section 20, Milestone 5). */
    List<String> allTrackedAccountIds(int limit);
}

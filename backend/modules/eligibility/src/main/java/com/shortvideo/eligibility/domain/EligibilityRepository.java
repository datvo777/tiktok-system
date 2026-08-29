package com.shortvideo.eligibility.domain;

import com.shortvideo.eligibility.api.AccountEligibilityView;
import com.shortvideo.eligibility.api.VideoEligibilityView;
import java.sql.Timestamp;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Version-guarded, per-source upserts (brief section 17): three independent
 * event sources (processing, moderation, publication) write into the same
 * video_eligibility row, each touching only its own columns and guarded by its
 * own version column, so replaying an older event from one source can never
 * clobber a newer write from another. {@code is_video_eligible} is recomputed
 * from the merged row after any source's write.
 */
@Repository
class EligibilityRepository {

    private static final String UPSERT_PROCESSING = """
            INSERT INTO eligibility.video_eligibility (
                video_id, creator_id, processing_state, processing_version, durability_state,
                asset_lifecycle_state, legal_serving_state, moderation_state, publication_state,
                publication_intent_requested, is_video_eligible, processing_version_source,
                moderation_version_source, publication_version_source, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, 'PENDING', 'PRIVATE', false, false, ?, 0, 0, ?)
            ON CONFLICT (video_id) DO UPDATE SET
                creator_id = EXCLUDED.creator_id,
                processing_state = EXCLUDED.processing_state,
                processing_version = EXCLUDED.processing_version,
                durability_state = EXCLUDED.durability_state,
                asset_lifecycle_state = EXCLUDED.asset_lifecycle_state,
                legal_serving_state = EXCLUDED.legal_serving_state,
                processing_version_source = EXCLUDED.processing_version_source,
                updated_at = EXCLUDED.updated_at
            WHERE eligibility.video_eligibility.processing_version_source < EXCLUDED.processing_version_source
            """;

    /**
     * assetLifecycleState-only write (Milestone 6): guarded by the same
     * processing_version_source column as {@link #UPSERT_PROCESSING} — a
     * lifecycle change bumps the video aggregate's own version, the same
     * stream processing writes to — but never touches processing_state,
     * processing_version, or durability_state, which this signal says nothing
     * about.
     */
    private static final String UPSERT_ASSET_LIFECYCLE = """
            INSERT INTO eligibility.video_eligibility (
                video_id, creator_id, processing_state, processing_version, durability_state,
                asset_lifecycle_state, legal_serving_state, moderation_state, publication_state,
                publication_intent_requested, is_video_eligible, processing_version_source,
                moderation_version_source, publication_version_source, updated_at
            ) VALUES (?, ?, 'CREATED', NULL, 'PENDING', ?, 'CLEAR', 'PENDING', 'PRIVATE', false, false, ?, 0, 0, ?)
            ON CONFLICT (video_id) DO UPDATE SET
                creator_id = EXCLUDED.creator_id,
                asset_lifecycle_state = EXCLUDED.asset_lifecycle_state,
                processing_version_source = EXCLUDED.processing_version_source,
                updated_at = EXCLUDED.updated_at
            WHERE eligibility.video_eligibility.processing_version_source < EXCLUDED.processing_version_source
            """;

    private static final String UPSERT_MODERATION = """
            INSERT INTO eligibility.video_eligibility (
                video_id, creator_id, processing_state, durability_state, asset_lifecycle_state,
                legal_serving_state, moderation_state, publication_state, publication_intent_requested,
                is_video_eligible, processing_version_source, moderation_version_source,
                publication_version_source, updated_at
            ) VALUES (?, ?, 'CREATED', 'PENDING', 'ACTIVE', 'CLEAR', ?, 'PRIVATE', false, false, 0, ?, 0, ?)
            ON CONFLICT (video_id) DO UPDATE SET
                creator_id = EXCLUDED.creator_id,
                moderation_state = EXCLUDED.moderation_state,
                moderation_version_source = EXCLUDED.moderation_version_source,
                updated_at = EXCLUDED.updated_at
            WHERE eligibility.video_eligibility.moderation_version_source < EXCLUDED.moderation_version_source
            """;

    private static final String UPSERT_PUBLICATION = """
            INSERT INTO eligibility.video_eligibility (
                video_id, creator_id, processing_state, durability_state, asset_lifecycle_state,
                legal_serving_state, moderation_state, publication_state, publication_intent_requested,
                is_video_eligible, processing_version_source, moderation_version_source,
                publication_version_source, updated_at
            ) VALUES (?, ?, 'CREATED', 'PENDING', 'ACTIVE', 'CLEAR', 'PENDING', ?, ?, false, 0, 0, ?, ?)
            ON CONFLICT (video_id) DO UPDATE SET
                creator_id = EXCLUDED.creator_id,
                publication_state = EXCLUDED.publication_state,
                publication_intent_requested = EXCLUDED.publication_intent_requested,
                publication_version_source = EXCLUDED.publication_version_source,
                updated_at = EXCLUDED.updated_at
            WHERE eligibility.video_eligibility.publication_version_source < EXCLUDED.publication_version_source
            """;

    private static final String RECOMPUTE_ELIGIBILITY = """
            UPDATE eligibility.video_eligibility
            SET is_video_eligible = (
                processing_state = 'READY'
                AND durability_state = 'DURABLE'
                AND asset_lifecycle_state = 'ACTIVE'
                AND legal_serving_state = 'CLEAR'
                AND moderation_state IN ('APPROVED', 'REINSTATED')
                AND publication_state = 'PUBLISHED'
                AND publication_intent_requested = true
            )
            WHERE video_id = ?
            """;

    private static final String FIND_VIDEO = """
            SELECT video_id, creator_id, processing_state, processing_version, durability_state,
                   moderation_state, publication_state, publication_intent_requested, asset_lifecycle_state,
                   legal_serving_state, is_video_eligible, processing_version_source,
                   moderation_version_source, publication_version_source, updated_at
            FROM eligibility.video_eligibility WHERE video_id = ?
            """;

    private static final String UPSERT_ACCOUNT = """
            INSERT INTO eligibility.account_eligibility (
                account_id, account_state, is_account_eligible, source_version, updated_at
            ) VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (account_id) DO UPDATE SET
                account_state = EXCLUDED.account_state,
                is_account_eligible = EXCLUDED.is_account_eligible,
                source_version = EXCLUDED.source_version,
                updated_at = EXCLUDED.updated_at
            WHERE eligibility.account_eligibility.source_version < EXCLUDED.source_version
            """;

    private static final String FIND_ACCOUNT = """
            SELECT account_id, account_state, is_account_eligible, source_version, updated_at
            FROM eligibility.account_eligibility WHERE account_id = ?
            """;

    private static final String ALL_VIDEO_IDS =
            "SELECT video_id FROM eligibility.video_eligibility ORDER BY updated_at LIMIT ?";

    private static final String ALL_ACCOUNT_IDS =
            "SELECT account_id FROM eligibility.account_eligibility ORDER BY updated_at LIMIT ?";

    private static final String FIND_ELIGIBLE = """
            SELECT video_id, creator_id, processing_state, processing_version, durability_state,
                   moderation_state, publication_state, publication_intent_requested, asset_lifecycle_state,
                   legal_serving_state, is_video_eligible, processing_version_source,
                   moderation_version_source, publication_version_source, updated_at
            FROM eligibility.video_eligibility
            WHERE is_video_eligible = true
            ORDER BY updated_at DESC
            LIMIT ?
            """;

    private final JdbcTemplate jdbc;

    EligibilityRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void upsertProcessing(
            String videoId,
            String creatorId,
            String processingState,
            Integer processingVersion,
            String durabilityState,
            String assetLifecycleState,
            String legalServingState,
            long sourceVersion,
            Timestamp updatedAt) {
        jdbc.update(
                UPSERT_PROCESSING,
                videoId,
                creatorId,
                processingState,
                processingVersion,
                durabilityState,
                assetLifecycleState,
                legalServingState,
                sourceVersion,
                updatedAt);
    }

    void upsertAssetLifecycle(
            String videoId, String creatorId, String assetLifecycleState, long sourceVersion, Timestamp updatedAt) {
        jdbc.update(UPSERT_ASSET_LIFECYCLE, videoId, creatorId, assetLifecycleState, sourceVersion, updatedAt);
    }

    void upsertModeration(
            String videoId, String creatorId, String moderationState, long sourceVersion, Timestamp updatedAt) {
        jdbc.update(UPSERT_MODERATION, videoId, creatorId, moderationState, sourceVersion, updatedAt);
    }

    void upsertPublication(
            String videoId,
            String creatorId,
            String publicationState,
            boolean publicationIntentRequested,
            long sourceVersion,
            Timestamp updatedAt) {
        jdbc.update(
                UPSERT_PUBLICATION,
                videoId,
                creatorId,
                publicationState,
                publicationIntentRequested,
                sourceVersion,
                updatedAt);
    }

    void recomputeEligibility(String videoId) {
        jdbc.update(RECOMPUTE_ELIGIBILITY, videoId);
    }

    void upsertAccount(AccountEligibilityView view) {
        jdbc.update(
                UPSERT_ACCOUNT,
                view.accountId(),
                view.accountState(),
                view.isAccountEligible(),
                view.sourceVersion(),
                Timestamp.from(view.updatedAt()));
    }

    Optional<VideoEligibilityView> findVideo(String videoId) {
        return jdbc.query(FIND_VIDEO, EligibilityRepository::mapVideo, videoId).stream().findFirst();
    }

    Optional<AccountEligibilityView> findAccount(String accountId) {
        return jdbc.query(FIND_ACCOUNT, EligibilityRepository::mapAccount, accountId).stream().findFirst();
    }

    java.util.List<VideoEligibilityView> findEligible(int limit) {
        return jdbc.query(FIND_ELIGIBLE, EligibilityRepository::mapVideo, limit);
    }

    java.util.List<String> allVideoIds(int limit) {
        return jdbc.queryForList(ALL_VIDEO_IDS, String.class, limit);
    }

    java.util.List<String> allAccountIds(int limit) {
        return jdbc.queryForList(ALL_ACCOUNT_IDS, String.class, limit);
    }

    private static VideoEligibilityView mapVideo(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new VideoEligibilityView(
                rs.getString("video_id"),
                rs.getString("creator_id"),
                rs.getString("processing_state"),
                (Integer) rs.getObject("processing_version"),
                rs.getString("durability_state"),
                rs.getString("moderation_state"),
                rs.getString("publication_state"),
                rs.getBoolean("publication_intent_requested"),
                rs.getString("asset_lifecycle_state"),
                rs.getString("legal_serving_state"),
                rs.getBoolean("is_video_eligible"),
                rs.getLong("processing_version_source"),
                rs.getLong("moderation_version_source"),
                rs.getLong("publication_version_source"),
                rs.getTimestamp("updated_at").toInstant());
    }

    private static AccountEligibilityView mapAccount(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new AccountEligibilityView(
                rs.getString("account_id"),
                rs.getString("account_state"),
                rs.getBoolean("is_account_eligible"),
                rs.getLong("source_version"),
                rs.getTimestamp("updated_at").toInstant());
    }
}

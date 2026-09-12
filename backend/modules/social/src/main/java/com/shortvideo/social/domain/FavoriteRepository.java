package com.shortvideo.social.domain;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class FavoriteRepository {

    private static final String INSERT_COLLECTION = """
            INSERT INTO social.favorite_collection (collection_id, account_id, name, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?)
            """;

    private static final String RENAME_COLLECTION = """
            UPDATE social.favorite_collection SET name = ?, updated_at = ?
            WHERE collection_id = ? AND account_id = ?
            """;

    private static final String DELETE_COLLECTION =
            "DELETE FROM social.favorite_collection WHERE collection_id = ? AND account_id = ?";

    /** One grouped pass over the items instead of a correlated count per row. */
    private static final String LIST_COLLECTIONS = """
            SELECT c.collection_id, c.name, c.created_at, c.updated_at,
                   count(i.video_id) AS item_count,
                   false AS contains_video
            FROM social.favorite_collection c
            LEFT JOIN social.favorite_item i ON i.collection_id = c.collection_id
            WHERE c.account_id = ?
            GROUP BY c.collection_id, c.name, c.created_at, c.updated_at
            ORDER BY c.created_at
            """;

    /**
     * The save-picker's variant: same list, plus whether each collection already
     * holds the video being saved. Kept as its own statement rather than binding
     * a nullable video id into {@link #LIST_COLLECTIONS} — an untyped NULL
     * parameter is exactly what PostgreSQL refuses to infer a type for.
     */
    private static final String LIST_COLLECTIONS_FOR_VIDEO = """
            SELECT c.collection_id, c.name, c.created_at, c.updated_at,
                   count(i.video_id) AS item_count,
                   bool_or(i.video_id = ?) AS contains_video
            FROM social.favorite_collection c
            LEFT JOIN social.favorite_item i ON i.collection_id = c.collection_id
            WHERE c.account_id = ?
            GROUP BY c.collection_id, c.name, c.created_at, c.updated_at
            ORDER BY c.created_at
            """;

    private static final String FIND_COLLECTION = """
            SELECT c.collection_id, c.name, c.created_at, c.updated_at,
                   (SELECT count(*) FROM social.favorite_item i WHERE i.collection_id = c.collection_id) AS item_count,
                   false AS contains_video
            FROM social.favorite_collection c
            WHERE c.collection_id = ? AND c.account_id = ?
            """;

    private static final String COUNT_COLLECTIONS =
            "SELECT count(*) FROM social.favorite_collection WHERE account_id = ?";

    private static final String COUNT_ITEMS = "SELECT count(*) FROM social.favorite_item WHERE collection_id = ?";

    private static final String ADD_ITEM = """
            INSERT INTO social.favorite_item (collection_id, video_id, created_at)
            VALUES (?, ?, ?)
            ON CONFLICT (collection_id, video_id) DO NOTHING
            """;

    private static final String REMOVE_ITEM =
            "DELETE FROM social.favorite_item WHERE collection_id = ? AND video_id = ?";

    private static final String LIST_ITEMS = """
            SELECT video_id, created_at FROM social.favorite_item
            WHERE collection_id = ?
            ORDER BY created_at DESC
            LIMIT 500
            """;

    private static final String TOUCH_COLLECTION =
            "UPDATE social.favorite_collection SET updated_at = ? WHERE collection_id = ?";

    private static final String IS_SAVED_ANYWHERE = """
            SELECT EXISTS (
                SELECT 1 FROM social.favorite_item i
                JOIN social.favorite_collection c ON c.collection_id = i.collection_id
                WHERE c.account_id = ? AND i.video_id = ?
            )
            """;

    private final JdbcTemplate jdbc;

    FavoriteRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** @return empty when the owner already has a collection by this name. */
    Optional<FavoriteViews.Collection> createCollection(String accountId, String name) {
        UUID collectionId = UUID.randomUUID();
        Instant now = Instant.now();
        try {
            jdbc.update(
                    INSERT_COLLECTION,
                    collectionId,
                    UUID.fromString(accountId),
                    name,
                    Timestamp.from(now),
                    Timestamp.from(now));
        } catch (DuplicateKeyException e) {
            // The unique index on (account_id, lower(name)) is the arbiter rather
            // than a SELECT-then-INSERT, which two concurrent saves could both pass.
            return Optional.empty();
        }
        return Optional.of(new FavoriteViews.Collection(collectionId.toString(), name, 0, false, now, now));
    }

    /**
     * @return false when the caller owns no such collection.
     * @throws DuplicateKeyException when the owner already has a collection by this name.
     */
    boolean rename(String collectionId, String accountId, String name) {
        int rows = jdbc.update(
                RENAME_COLLECTION,
                name,
                Timestamp.from(Instant.now()),
                UUID.fromString(collectionId),
                UUID.fromString(accountId));
        return rows > 0;
    }

    boolean deleteCollection(String collectionId, String accountId) {
        return jdbc.update(DELETE_COLLECTION, UUID.fromString(collectionId), UUID.fromString(accountId)) > 0;
    }

    List<FavoriteViews.Collection> listCollections(String accountId) {
        return jdbc.query(LIST_COLLECTIONS, FavoriteRepository::mapCollection, UUID.fromString(accountId));
    }

    /** As {@link #listCollections}, with each row reporting whether it already holds {@code videoId}. */
    List<FavoriteViews.Collection> listCollectionsFor(String accountId, String videoId) {
        return jdbc.query(
                LIST_COLLECTIONS_FOR_VIDEO,
                FavoriteRepository::mapCollection,
                UUID.fromString(videoId),
                UUID.fromString(accountId));
    }

    Optional<FavoriteViews.Collection> findCollection(String collectionId, String accountId) {
        return jdbc
                .query(FIND_COLLECTION, FavoriteRepository::mapCollection, UUID.fromString(collectionId), UUID.fromString(accountId))
                .stream()
                .findFirst();
    }

    long countCollections(String accountId) {
        Long count = jdbc.queryForObject(COUNT_COLLECTIONS, Long.class, UUID.fromString(accountId));
        return count == null ? 0 : count;
    }

    long countItems(String collectionId) {
        Long count = jdbc.queryForObject(COUNT_ITEMS, Long.class, UUID.fromString(collectionId));
        return count == null ? 0 : count;
    }

    /** @return true if the video was not already in the collection. */
    boolean addItem(String collectionId, String videoId) {
        int rows = jdbc.update(
                ADD_ITEM, UUID.fromString(collectionId), UUID.fromString(videoId), Timestamp.from(Instant.now()));
        if (rows > 0) {
            jdbc.update(TOUCH_COLLECTION, Timestamp.from(Instant.now()), UUID.fromString(collectionId));
        }
        return rows > 0;
    }

    void removeItem(String collectionId, String videoId) {
        if (jdbc.update(REMOVE_ITEM, UUID.fromString(collectionId), UUID.fromString(videoId)) > 0) {
            jdbc.update(TOUCH_COLLECTION, Timestamp.from(Instant.now()), UUID.fromString(collectionId));
        }
    }

    /** Saved video ids with the time they were saved, newest first. */
    List<SavedRow> listItems(String collectionId) {
        return jdbc.query(
                LIST_ITEMS,
                (rs, rowNum) -> new SavedRow(rs.getString("video_id"), rs.getTimestamp("created_at").toInstant()),
                UUID.fromString(collectionId));
    }

    boolean isSavedAnywhere(String accountId, String videoId) {
        Boolean saved = jdbc.queryForObject(
                IS_SAVED_ANYWHERE, Boolean.class, UUID.fromString(accountId), UUID.fromString(videoId));
        return Boolean.TRUE.equals(saved);
    }

    record SavedRow(String videoId, Instant savedAt) {}

    private static FavoriteViews.Collection mapCollection(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new FavoriteViews.Collection(
                rs.getString("collection_id"),
                rs.getString("name"),
                rs.getLong("item_count"),
                rs.getBoolean("contains_video"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }
}

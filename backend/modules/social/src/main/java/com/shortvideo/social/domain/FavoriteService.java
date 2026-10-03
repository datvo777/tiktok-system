package com.shortvideo.social.domain;

import com.shortvideo.account.api.AccountDirectory;
import com.shortvideo.account.api.AccountView;
import com.shortvideo.eligibility.api.EligibilityDirectory;
import com.shortvideo.eligibility.api.VideoEligibilityView;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Favorite collections: a viewer's private folders of saved videos.
 *
 * <p>Separate from {@link SocialService} rather than another handful of methods
 * on it. Likes, comments, follows and shares are all single-verb interactions
 * against a video or an account; collections are an owned aggregate with their
 * own lifecycle, name uniqueness and membership rules, and the two share no
 * state beyond the module's schema.
 *
 * <p><b>Privacy.</b> Every read and write is scoped by the caller's account id in
 * the SQL itself, not filtered after the fact — a collection id belonging to
 * someone else answers "not found" rather than returning their saved videos.
 */
@Service
public class FavoriteService {

    /**
     * The collection a first save lands in when the viewer hasn't made one yet,
     * so "save this" never has to start with "first, name a folder".
     */
    static final String DEFAULT_COLLECTION_NAME = "Favorites";

    /** Bounds on an owner's own data: enough for real use, not enough to be a storage lever. */
    private static final int MAX_COLLECTIONS = 50;
    private static final int MAX_ITEMS_PER_COLLECTION = 500;

    private final FavoriteRepository repository;
    private final EligibilityDirectory eligibilityDirectory;
    private final AccountDirectory accountDirectory;

    public FavoriteService(
            FavoriteRepository repository,
            EligibilityDirectory eligibilityDirectory,
            AccountDirectory accountDirectory) {
        this.repository = repository;
        this.eligibilityDirectory = eligibilityDirectory;
        this.accountDirectory = accountDirectory;
    }

    @Transactional
    public FavoriteViews.Collection createCollection(String accountId, String name) {
        String trimmed = requireName(name);
        if (repository.countCollections(accountId) >= MAX_COLLECTIONS) {
            throw new SocialExceptions.CollectionLimitReached(
                    "You can have at most " + MAX_COLLECTIONS + " collections");
        }
        return repository
                .createCollection(accountId, trimmed)
                .orElseThrow(() -> new SocialExceptions.CollectionNameTaken(
                        "You already have a collection called \"" + trimmed + "\""));
    }

    @Transactional
    public FavoriteViews.Collection renameCollection(String collectionId, String accountId, String name) {
        String trimmed = requireName(name);
        boolean renamed;
        try {
            renamed = repository.rename(collectionId, accountId, trimmed);
        } catch (DuplicateKeyException e) {
            throw new SocialExceptions.CollectionNameTaken(
                    "You already have a collection called \"" + trimmed + "\"");
        }
        if (!renamed) {
            throw new SocialExceptions.CollectionNotFound("No such collection");
        }
        return requireCollection(collectionId, accountId);
    }

    /** Deleting a collection drops its saved rows (ON DELETE CASCADE), never the videos themselves. */
    @Transactional
    public void deleteCollection(String collectionId, String accountId) {
        if (!repository.deleteCollection(collectionId, accountId)) {
            throw new SocialExceptions.CollectionNotFound("No such collection");
        }
    }

    @Transactional(readOnly = true)
    public List<FavoriteViews.Collection> listCollections(String accountId) {
        return repository.listCollections(accountId);
    }

    /**
     * The save-picker's list: every collection the caller owns, each flagged with
     * whether it already holds this video, so the sheet can render checkmarks
     * without a second round trip per row.
     */
    @Transactional(readOnly = true)
    public List<FavoriteViews.Collection> listCollectionsFor(String accountId, String videoId) {
        requireEligible(videoId);
        return repository.listCollectionsFor(accountId, videoId);
    }

    /**
     * Saves a video, creating the default collection on the first save so the
     * caller can offer a one-tap "save" without a folder prompt.
     *
     * @param collectionId the target collection, or null for the default one.
     */
    @Transactional
    public FavoriteViews.Collection save(String accountId, String videoId, String collectionId) {
        requireEligible(videoId);
        FavoriteViews.Collection target =
                collectionId == null ? defaultCollection(accountId) : requireCollection(collectionId, accountId);
        if (repository.countItems(target.collectionId()) >= MAX_ITEMS_PER_COLLECTION) {
            throw new SocialExceptions.CollectionLimitReached(
                    "\"" + target.name() + "\" is full (" + MAX_ITEMS_PER_COLLECTION + " videos)");
        }
        repository.addItem(target.collectionId(), videoId);
        return requireCollection(target.collectionId(), accountId);
    }

    /** Idempotent: removing a video that isn't in the collection is a no-op, not an error. */
    @Transactional
    public void unsave(String accountId, String videoId, String collectionId) {
        requireCollection(collectionId, accountId);
        repository.removeItem(collectionId, videoId);
    }

    /** Whether the caller has this video in any of their collections — the feed's bookmark state. */
    @Transactional(readOnly = true)
    public boolean isSaved(String accountId, String videoId) {
        return repository.isSavedAnywhere(accountId, videoId);
    }

    /**
     * A collection and its playable videos.
     *
     * <p>Saved videos that are no longer eligible (taken down, suspended creator,
     * asset removed) are counted but not listed. Listing them would offer rows
     * that cannot play; dropping them silently would make a collection appear to
     * lose items on its own, so the count is reported instead.
     */
    @Transactional(readOnly = true)
    public FavoriteViews.Detail collection(String collectionId, String accountId) {
        FavoriteViews.Collection collection = requireCollection(collectionId, accountId);
        List<FavoriteRepository.SavedRow> saved = repository.listItems(collectionId);
        if (saved.isEmpty()) {
            return new FavoriteViews.Detail(collection, List.of(), 0);
        }

        // One eligibility query and one account lookup per distinct creator for
        // the whole page, rather than a pair of round trips per saved video.
        Set<String> videoIds = saved.stream().map(FavoriteRepository.SavedRow::videoId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<String, VideoEligibilityView> eligible = eligibilityDirectory.findVideoEligibilities(videoIds).stream()
                .filter(VideoEligibilityView::isVideoEligible)
                .collect(Collectors.toMap(VideoEligibilityView::videoId, view -> view, (a, b) -> a));

        Map<String, String> creatorNames = new HashMap<>();
        for (VideoEligibilityView view : eligible.values()) {
            creatorNames.computeIfAbsent(
                    view.creatorId(),
                    id -> accountDirectory.find(id).filter(AccountView::isEligible).map(AccountView::displayName).orElse(null));
        }

        List<FavoriteViews.Item> items = saved.stream()
                .map(row -> {
                    VideoEligibilityView view = eligible.get(row.videoId());
                    // A creator whose account is no longer eligible drops the row
                    // for the same reason an ineligible video does.
                    String creatorName = view == null ? null : creatorNames.get(view.creatorId());
                    return view == null || creatorName == null
                            ? null
                            : new FavoriteViews.Item(
                                    view.videoId(),
                                    view.creatorId(),
                                    creatorName,
                                    view.title(),
                                    view.description(),
                                    row.savedAt());
                })
                .filter(java.util.Objects::nonNull)
                .toList();

        return new FavoriteViews.Detail(collection, items, saved.size() - items.size());
    }

    /**
     * Two saves racing on a viewer's very first one both find no default and both
     * try to create it; the unique index lets exactly one win. Answering the
     * loser with "name already taken" would be a nonsense error for a save, so it
     * re-reads and uses the collection the winner just made.
     */
    private FavoriteViews.Collection defaultCollection(String accountId) {
        Optional<FavoriteViews.Collection> existing = findDefault(accountId);
        if (existing.isPresent()) {
            return existing.get();
        }
        try {
            return createCollection(accountId, DEFAULT_COLLECTION_NAME);
        } catch (SocialExceptions.CollectionNameTaken e) {
            return findDefault(accountId)
                    .orElseThrow(() -> new SocialExceptions.CollectionNotFound("No such collection"));
        }
    }

    private Optional<FavoriteViews.Collection> findDefault(String accountId) {
        return repository.listCollections(accountId).stream()
                .filter(c -> c.name().equalsIgnoreCase(DEFAULT_COLLECTION_NAME))
                .findFirst();
    }

    private FavoriteViews.Collection requireCollection(String collectionId, String accountId) {
        return repository
                .findCollection(collectionId, accountId)
                .orElseThrow(() -> new SocialExceptions.CollectionNotFound("No such collection"));
    }

    private String requireName(String name) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            throw new SocialExceptions.InvalidCollectionName("A collection needs a name");
        }
        return trimmed;
    }

    private void requireEligible(String videoId) {
        eligibilityDirectory
                .findVideoEligibility(videoId)
                .filter(VideoEligibilityView::isVideoEligible)
                .orElseThrow(() -> new SocialExceptions.VideoNotEligible("Video is not available"));
    }
}

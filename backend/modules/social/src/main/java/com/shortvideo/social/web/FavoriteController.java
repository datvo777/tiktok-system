package com.shortvideo.social.web;

import com.shortvideo.shared.security.AuthenticatedAccount;
import com.shortvideo.social.domain.FavoriteService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Favorite collections (brief section 20). Everything here is scoped to the
 * caller's own account — there is no path that names another account's
 * collections, so a saved list cannot be read by anyone but its owner.
 */
@RestController
@RequestMapping("/api/v1/favorites")
@Tag(name = "Favorites")
public class FavoriteController {

    private final FavoriteService favoriteService;

    public FavoriteController(FavoriteService favoriteService) {
        this.favoriteService = favoriteService;
    }

    @GetMapping("/collections")
    @Operation(summary = "The caller's favorite collections; pass videoId to also learn which ones hold that video")
    public ResponseEntity<FavoriteDtos.CollectionListResponse> listCollections(
            @RequestParam(required = false) UUID videoId, @AuthenticationPrincipal AuthenticatedAccount caller) {
        var collections = videoId == null
                ? favoriteService.listCollections(caller.accountId())
                : favoriteService.listCollectionsFor(caller.accountId(), videoId.toString());
        return ResponseEntity.ok(FavoriteDtos.CollectionListResponse.from(collections));
    }

    @PostMapping("/collections")
    @Operation(summary = "Create a favorite collection")
    public ResponseEntity<FavoriteDtos.CollectionResponse> createCollection(
            @AuthenticationPrincipal AuthenticatedAccount caller,
            @Valid @RequestBody FavoriteDtos.CollectionNameRequest request) {
        var created = favoriteService.createCollection(caller.accountId(), request.name());
        return ResponseEntity.status(201).body(FavoriteDtos.CollectionResponse.from(created));
    }

    @PatchMapping("/collections/{collectionId}")
    @Operation(summary = "Rename a favorite collection")
    public ResponseEntity<FavoriteDtos.CollectionResponse> renameCollection(
            @PathVariable UUID collectionId,
            @AuthenticationPrincipal AuthenticatedAccount caller,
            @Valid @RequestBody FavoriteDtos.CollectionNameRequest request) {
        var renamed = favoriteService.renameCollection(collectionId.toString(), caller.accountId(), request.name());
        return ResponseEntity.ok(FavoriteDtos.CollectionResponse.from(renamed));
    }

    @DeleteMapping("/collections/{collectionId}")
    @Operation(summary = "Delete a favorite collection and its saved rows; the videos themselves are untouched")
    public ResponseEntity<Void> deleteCollection(
            @PathVariable UUID collectionId, @AuthenticationPrincipal AuthenticatedAccount caller) {
        favoriteService.deleteCollection(collectionId.toString(), caller.accountId());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/collections/{collectionId}")
    @Operation(summary = "A collection with its currently playable videos, most recently saved first")
    public ResponseEntity<FavoriteDtos.CollectionDetailResponse> collection(
            @PathVariable UUID collectionId, @AuthenticationPrincipal AuthenticatedAccount caller) {
        return ResponseEntity.ok(FavoriteDtos.CollectionDetailResponse.from(
                favoriteService.collection(collectionId.toString(), caller.accountId())));
    }

    @PostMapping("/videos/{videoId}")
    @Operation(summary = "Save a video to a collection, or to the default one when none is named; idempotent")
    public ResponseEntity<FavoriteDtos.CollectionResponse> save(
            @PathVariable UUID videoId,
            @AuthenticationPrincipal AuthenticatedAccount caller,
            @RequestBody(required = false) FavoriteDtos.SaveVideoRequest request) {
        String collectionId = request == null ? null : request.collectionId();
        var collection = favoriteService.save(caller.accountId(), videoId.toString(), collectionId);
        return ResponseEntity.ok(FavoriteDtos.CollectionResponse.from(collection));
    }

    @DeleteMapping("/collections/{collectionId}/videos/{videoId}")
    @Operation(summary = "Remove a video from a collection; idempotent")
    public ResponseEntity<Void> unsave(
            @PathVariable UUID collectionId,
            @PathVariable UUID videoId,
            @AuthenticationPrincipal AuthenticatedAccount caller) {
        favoriteService.unsave(caller.accountId(), videoId.toString(), collectionId.toString());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/videos/{videoId}")
    @Operation(summary = "Whether the caller has this video saved in any collection")
    public ResponseEntity<FavoriteDtos.SavedStateResponse> savedState(
            @PathVariable UUID videoId, @AuthenticationPrincipal AuthenticatedAccount caller) {
        return ResponseEntity.ok(
                new FavoriteDtos.SavedStateResponse(favoriteService.isSaved(caller.accountId(), videoId.toString())));
    }
}

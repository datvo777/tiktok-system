package com.shortvideo.playback;

import com.shortvideo.eligibility.api.AccountEligibilityView;
import com.shortvideo.eligibility.api.EligibilityDirectory;
import com.shortvideo.eligibility.api.VideoEligibilityView;
import com.shortvideo.shared.revocation.DurableRevocationReader;
import com.shortvideo.shared.revocation.RevocationSubjects;
import com.shortvideo.shared.security.AuthenticatedAccount;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Optional;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * Serves a video's thumbnail.
 *
 * <p><b>Why this is not the media gateway.</b> The gateway authorizes every
 * request against a video-scoped playback cookie, which is right for the video
 * itself and impossible for a thumbnail: a search results grid shows twenty
 * videos, and minting twenty playback sessions to draw twenty small images
 * defeats the point of having thumbnails at all. This endpoint instead makes the
 * same ranking-tier eligibility decision the feed makes — the video and its
 * creator must both be eligible, and neither revoked — which is exactly the set
 * of videos whose existence the feed is already willing to disclose.
 *
 * <p>It never serves video bytes: the object key it builds always ends in the
 * worker's fixed poster filename, so this cannot be turned into an unauthorized
 * route to a media segment by manipulating the request.
 *
 * <p>The processing version is resolved server-side from the eligibility
 * projection rather than taken from the caller, so the URL a client holds is
 * just the video id and stays correct across a reprocess.
 */
@RestController
@RequestMapping("/api/v1/videos")
@Tag(name = "Media gateway")
public class PosterController {

    /** Matches what {@code HlsTranscoder} writes at the root of each version prefix. */
    private static final String POSTER_FILENAME = "poster.jpg";

    private final EligibilityDirectory eligibilityDirectory;
    private final DurableRevocationReader revocationReader;
    private final MediaPathValidator pathValidator;
    private final MediaObjectStreamer streamer;

    PosterController(
            EligibilityDirectory eligibilityDirectory,
            DurableRevocationReader revocationReader,
            MediaPathValidator pathValidator,
            MediaObjectStreamer streamer) {
        this.eligibilityDirectory = eligibilityDirectory;
        this.revocationReader = revocationReader;
        this.pathValidator = pathValidator;
        this.streamer = streamer;
    }

    /**
     * 404 covers every negative case — no such video, not eligible, revoked, or
     * processed before thumbnails existed. The client falls back to its
     * placeholder tile either way, and a status code is not a good place to
     * disclose which of those it was.
     */
    @GetMapping("/{videoId}/poster")
    @Operation(summary = "Thumbnail for a publicly eligible video")
    public ResponseEntity<StreamingResponseBody> poster(
            @PathVariable String videoId, @AuthenticationPrincipal AuthenticatedAccount viewer) {

        VideoEligibilityView video = eligibleVideo(videoId)
                .orElseThrow(() -> new MediaAuthorizationException.ObjectMissing("No thumbnail for this video"));

        MediaObjectKey key = pathValidator.validate(
                video.videoId(), String.valueOf(video.processingVersion()), POSTER_FILENAME);

        MediaObjectStreamer.PreparedStream prepared = streamer.prepare(key, null);

        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .contentLength(prepared.contentLength())
                // Unlike media segments, a thumbnail may be cached: it is a
                // derived still of already-public content, it is immutable for a
                // given processing version, and a grid that re-fetches every
                // tile on every render is the problem this was added to solve.
                // Private, because eligibility can still be withdrawn.
                .cacheControl(CacheControl.maxAge(java.time.Duration.ofMinutes(30)).cachePrivate())
                .header(HttpHeaders.VARY, HttpHeaders.COOKIE)
                .body(prepared.body());
    }

    /** The same conjunction the feed applies, so a thumbnail cannot outlive its video's eligibility. */
    private Optional<VideoEligibilityView> eligibleVideo(String videoId) {
        Optional<VideoEligibilityView> video = eligibilityDirectory
                .findVideoEligibility(videoId)
                .filter(VideoEligibilityView::isVideoEligible)
                .filter(v -> v.processingVersion() != null);
        if (video.isEmpty()) {
            return Optional.empty();
        }
        VideoEligibilityView view = video.get();

        boolean creatorEligible = eligibilityDirectory
                .findAccountEligibility(view.creatorId())
                .map(AccountEligibilityView::isAccountEligible)
                .orElse(false); // unknown state denies (Rule 9)
        if (!creatorEligible) {
            return Optional.empty();
        }

        if (!revocationReader.activeAmong(RevocationSubjects.VIDEO, List.of(view.videoId())).isEmpty()
                || !revocationReader.activeAmong(RevocationSubjects.ACCOUNT, List.of(view.creatorId())).isEmpty()) {
            return Optional.empty();
        }
        return video;
    }
}

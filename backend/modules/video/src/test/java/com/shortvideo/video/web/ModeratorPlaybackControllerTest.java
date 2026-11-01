package com.shortvideo.video.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.shortvideo.shared.revocation.DurableRevocationReader;
import com.shortvideo.shared.revocation.RevocationCache;
import com.shortvideo.shared.revocation.RevocationSources;
import com.shortvideo.shared.revocation.RevocationSubjects;
import com.shortvideo.shared.security.AuthenticatedAccount;
import com.shortvideo.shared.security.PlaybackCookies;
import com.shortvideo.shared.security.PlaybackTokenService;
import com.shortvideo.video.api.AssetLifecycleState;
import com.shortvideo.video.api.DurabilityState;
import com.shortvideo.video.api.LegalServingState;
import com.shortvideo.video.api.ProcessingState;
import com.shortvideo.video.api.VideoPlaybackView;
import com.shortvideo.video.domain.VideoExceptions;
import com.shortvideo.video.domain.VideoService;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseCookie;

class ModeratorPlaybackControllerTest {

    private final VideoService videoService = mock(VideoService.class);
    private final RevocationCache cache = mock(RevocationCache.class);
    private final DurableRevocationReader reader = mock(DurableRevocationReader.class);
    private final PlaybackTokenService tokenService = mock(PlaybackTokenService.class);
    private final PlaybackCookies cookies = mock(PlaybackCookies.class);
    private final ModeratorPlaybackController controller =
            new ModeratorPlaybackController(videoService, cache, reader, tokenService, cookies);

    private final UUID videoId = UUID.randomUUID();
    private final String owner = UUID.randomUUID().toString();
    private final String staff = UUID.randomUUID().toString();
    private final AuthenticatedAccount caller = new AuthenticatedAccount(
            staff, Set.of("USER", "MODERATOR"), "t", Instant.now(), Instant.now().plusSeconds(60), Instant.now());

    private void video(AssetLifecycleState lifecycle, ProcessingState processing) {
        when(videoService.findForPlayback(videoId.toString()))
                .thenReturn(Optional.of(new VideoPlaybackView(
                        videoId.toString(), owner, processing, 1, DurabilityState.DURABLE, lifecycle,
                        LegalServingState.CLEAR, 1)));
        when(tokenService.issue(anyString(), anyString(), anyInt(), anyString()))
                .thenReturn(new PlaybackTokenService.IssuedPlaybackToken("tok", "sid", Instant.now().plusSeconds(60)));
        when(cookies.cookie(anyString(), any(), anyString(), anyInt()))
                .thenReturn(ResponseCookie.from("sv_playback", "tok").build());
    }

    /** Both the Redis fast path and the durable record report the same active sources. */
    private void revoked(String subjectType, String subjectId, String... sources) {
        when(cache.activeSources(subjectType, subjectId)).thenReturn(Set.of(sources));
        when(reader.activeSources(subjectType, subjectId)).thenReturn(Set.of(sources));
    }

    @Test
    void aReadyVideoGetsASession() {
        video(AssetLifecycleState.ACTIVE, ProcessingState.READY);

        var response = controller.moderatorPreview(videoId, caller);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
    }

    /** An appeal can only be decided by watching what was rejected. */
    @Test
    void aRejectedVideoRetainedForAppealCanBeWatchedDespiteItsRejection() {
        video(AssetLifecycleState.REJECTED_RETAINED, ProcessingState.READY);
        revoked(RevocationSubjects.VIDEO, videoId.toString(), RevocationSources.MODERATION);

        var response = controller.moderatorPreview(videoId, caller);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
    }

    @Test
    void aSuspendedAccountsVideoCanBeWatchedToInvestigateIt() {
        video(AssetLifecycleState.ACTIVE, ProcessingState.READY);
        revoked(RevocationSubjects.ACCOUNT, owner, RevocationSources.ACCOUNT_SUSPENSION);

        assertThat(controller.moderatorPreview(videoId, caller).getStatusCode().is2xxSuccessful()).isTrue();
    }

    @Test
    void aLifecycleHoldStillBlocksStaffAndNothingIsIssued() {
        video(AssetLifecycleState.ACTIVE, ProcessingState.READY);
        revoked(RevocationSubjects.VIDEO, videoId.toString(), RevocationSources.LIFECYCLE);

        assertThatThrownBy(() -> controller.moderatorPreview(videoId, caller))
                .isInstanceOf(VideoExceptions.VideoNotReady.class);

        verify(tokenService, never()).issue(anyString(), anyString(), anyInt(), anyString());
    }

    /** Looking past a rejection must not mean looking past everything else recorded against the video. */
    @Test
    void aSourceStaffAreNotMeantToLookPastStillBlocksEvenAlongsideARejection() {
        video(AssetLifecycleState.REJECTED_RETAINED, ProcessingState.READY);
        revoked(RevocationSubjects.VIDEO, videoId.toString(), RevocationSources.MODERATION, "LEGAL_HOLD");

        assertThatThrownBy(() -> controller.moderatorPreview(videoId, caller))
                .isInstanceOf(VideoExceptions.VideoNotReady.class);
    }

    @Test
    void aQuarantinedOrNotYetReadyVideoCannotBeWatched() {
        video(AssetLifecycleState.QUARANTINED, ProcessingState.READY);
        assertThatThrownBy(() -> controller.moderatorPreview(videoId, caller))
                .isInstanceOf(VideoExceptions.VideoNotReady.class);

        video(AssetLifecycleState.ACTIVE, ProcessingState.TRANSCODING);
        assertThatThrownBy(() -> controller.moderatorPreview(videoId, caller))
                .isInstanceOf(VideoExceptions.VideoNotReady.class);
    }

    @Test
    void anUnknownVideoIsNotFound() {
        when(videoService.findForPlayback(videoId.toString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.moderatorPreview(videoId, caller))
                .isInstanceOf(VideoExceptions.VideoNotFound.class);
    }
}

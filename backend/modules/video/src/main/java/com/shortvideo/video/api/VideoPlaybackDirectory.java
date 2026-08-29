package com.shortvideo.video.api;

import java.util.Optional;

/**
 * The Video module's synchronous interface for the media gateway (brief section 8).
 * A missing row is unknown state and denies (Rule 9); callers must not cache the
 * result as an authorization decision.
 */
public interface VideoPlaybackDirectory {

    Optional<VideoPlaybackView> findForPlayback(String videoId);
}

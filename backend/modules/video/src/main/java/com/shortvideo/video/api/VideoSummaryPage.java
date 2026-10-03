package com.shortvideo.video.api;

import java.util.List;

/** One page of {@link VideoSummaryView}s, most recent first. */
public record VideoSummaryPage(List<VideoSummaryView> items, boolean hasMore) {}

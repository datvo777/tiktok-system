package com.shortvideo.moderation.domain;

import java.util.List;

/** One page of {@link #items()}; {@code nextCursor} is null when this is the last page. */
public record ModerationPage(List<ModerationView> items, String nextCursor) {}

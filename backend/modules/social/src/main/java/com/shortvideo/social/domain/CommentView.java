package com.shortvideo.social.domain;

import java.time.Instant;

public record CommentView(String commentId, String videoId, String accountId, String body, Instant createdAt) {}

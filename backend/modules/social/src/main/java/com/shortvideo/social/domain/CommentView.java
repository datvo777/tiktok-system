package com.shortvideo.social.domain;

import java.time.Instant;
import java.util.List;

public record CommentView(
        String commentId,
        String videoId,
        String accountId,
        String body,
        Instant createdAt,
        String parentCommentId,
        long replyCount,
        /** @handle mentions resolved from the body at write time, in first-appearance order. */
        List<CommentMention> mentions) {}

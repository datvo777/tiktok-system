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
        /** Account ids resolved from @handle mentions in the body at write time. */
        List<String> mentionedAccountIds) {}

package com.shortvideo.appeal.domain;

import com.shortvideo.appeal.api.AppealState;
import java.time.Instant;

public record AppealView(
        String videoId, String creatorId, AppealState state, String reason, String decisionReason, Instant updatedAt) {}

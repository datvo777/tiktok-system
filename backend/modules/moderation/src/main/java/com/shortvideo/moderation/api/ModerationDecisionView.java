package com.shortvideo.moderation.api;

public record ModerationDecisionView(String videoId, String creatorId, String state, long aggregateVersion) {}

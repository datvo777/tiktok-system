package com.shortvideo.social.domain;

import com.shortvideo.social.api.SocialCounts;

public record VideoCountsView(SocialCounts counts, boolean liked) {}

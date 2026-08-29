package com.shortvideo.upload.domain;

import java.time.Instant;

public record UploadSessionCreated(String uploadId, String videoId, String uploadUrl, Instant expiresAt) {}

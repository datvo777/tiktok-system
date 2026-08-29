package com.shortvideo.playback;

/** A validated, gateway-safe reference to one processed object. */
public record MediaObjectKey(String videoId, int processingVersion, String assetPath, String objectKey) {}

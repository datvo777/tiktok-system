package com.shortvideo.playback;

import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Path validation for the media gateway (brief section 8).
 *
 * <p>The gateway never concatenates unchecked input into an object key. Every
 * request must resolve inside {@code processed/{videoId}/{processingVersion}/},
 * and the resolved key is re-checked against that prefix before it is used —
 * belt and braces, because a traversal bug here is a direct read of somebody
 * else's media.
 *
 * <p>The cookie Path controls when the browser sends the cookie; it is not an
 * authorization boundary, so this validation is not optional for any request.
 */
@Component
public class MediaPathValidator {

    public static final String PROCESSED_PREFIX = "processed";

    /** One path segment: no dots-only names, no slashes, no control characters. */
    private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

    private static final Set<String> ALLOWED_EXTENSIONS =
            Set.of("m3u8", "ts", "m4s", "mp4", "vtt", "jpg", "jpeg", "png");

    private static final int MAX_DEPTH = 3;

    public MediaObjectKey validate(String videoId, String processingVersion, String rawAssetPath) {
        String video = requireUuid(videoId);
        int version = requirePositiveInt(processingVersion);
        String assetPath = requireSafeAssetPath(rawAssetPath);

        String objectKey = PROCESSED_PREFIX + "/" + video + "/" + version + "/" + assetPath;
        String expectedPrefix = PROCESSED_PREFIX + "/" + video + "/" + version + "/";
        if (!objectKey.startsWith(expectedPrefix) || objectKey.contains("..")) {
            throw new InvalidMediaPathException("Resolved key escaped the authorized prefix");
        }
        return new MediaObjectKey(video, version, assetPath, objectKey);
    }

    private String requireUuid(String videoId) {
        if (videoId == null) {
            throw new InvalidMediaPathException("Missing videoId");
        }
        try {
            // Canonical form only: rejects surrounding whitespace and odd casing
            // that would otherwise produce two keys for one video.
            UUID parsed = UUID.fromString(videoId);
            if (!parsed.toString().equals(videoId.toLowerCase(Locale.ROOT))) {
                throw new InvalidMediaPathException("videoId is not in canonical UUID form");
            }
            return parsed.toString();
        } catch (IllegalArgumentException e) {
            throw new InvalidMediaPathException("videoId is not a UUID");
        }
    }

    private int requirePositiveInt(String processingVersion) {
        if (processingVersion == null || !processingVersion.matches("[1-9][0-9]{0,8}")) {
            throw new InvalidMediaPathException("processingVersion must be a positive integer");
        }
        return Integer.parseInt(processingVersion);
    }

    private String requireSafeAssetPath(String rawAssetPath) {
        if (rawAssetPath == null) {
            throw new InvalidMediaPathException("Missing asset path");
        }
        String assetPath = rawAssetPath.startsWith("/") ? rawAssetPath.substring(1) : rawAssetPath;

        if (assetPath.isBlank()) {
            throw new InvalidMediaPathException("Empty asset path");
        }
        // A '%' surviving servlet decoding means double encoding: refuse rather
        // than decode again and risk resolving "%252e%252e" into "..".
        if (assetPath.indexOf('%') >= 0
                || assetPath.indexOf('\\') >= 0
                || assetPath.indexOf('\0') >= 0
                || assetPath.contains("//")) {
            throw new InvalidMediaPathException("Asset path contains an illegal character");
        }

        String[] segments = assetPath.split("/");
        if (segments.length > MAX_DEPTH) {
            throw new InvalidMediaPathException("Asset path is too deep");
        }
        for (String segment : segments) {
            if (!SEGMENT.matcher(segment).matches()) {
                throw new InvalidMediaPathException("Illegal path segment: " + segment);
            }
        }

        String last = segments[segments.length - 1];
        int dot = last.lastIndexOf('.');
        if (dot <= 0 || dot == last.length() - 1) {
            throw new InvalidMediaPathException("Asset must have a file extension");
        }
        String extension = last.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new InvalidMediaPathException("Unsupported media extension: " + extension);
        }
        return assetPath;
    }
}

package com.shortvideo.video.domain;

import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.Result;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import io.minio.messages.Item;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Durability check (brief section 11.1, step 3): confirms the assets the worker
 * declared complete actually exist in MinIO before the video is marked DURABLE.
 * This is the local simulation of durability (Rule 14) — it proves the objects are
 * present, segments included, not that they are replicated anywhere.
 *
 * <p>"The object is not there" and "MinIO could not be asked" are different answers. The first
 * is a verdict (the video cannot be marked READY); the second is no verdict at all, so it is
 * thrown as {@link VideoExceptions.AssetStoreUnavailable} and the caller's unit of work, a Kafka
 * delivery in the case of a transcode result, fails and is tried again later. Folding it into
 * {@code false} would fail a perfectly good transcode because of a passing MinIO outage.
 */
@Component
class MinioAssetVerifier {

    private static final Logger log = LoggerFactory.getLogger(MinioAssetVerifier.class);
    private static final Set<String> MISSING_OBJECT = Set.of("NoSuchKey", "NoSuchObject");

    private final MinioClient minioClient;
    private final String bucket;

    MinioAssetVerifier(MinioClient minioClient, @Value("${shortvideo.minio.bucket}") String bucket) {
        this.minioClient = minioClient;
        this.bucket = bucket;
    }

    /**
     * @param expectedSegments how many {@code .ts} segments the worker said it wrote across all rungs;
     *                         zero or less skips the segment check (a video recorded before the count was kept)
     * @return true if every playlist exists and at least {@code expectedSegments} segments sit beside them;
     *         false if the master playlist is not given or any of that is missing
     * @throws VideoExceptions.AssetStoreUnavailable if MinIO could not answer
     */
    boolean verify(String masterPlaylistKey, List<String> variantPlaylistKeys, int expectedSegments) {
        if (masterPlaylistKey == null || masterPlaylistKey.isBlank()) {
            return false;
        }
        try {
            stat(masterPlaylistKey);
            for (String variant : variantPlaylistKeys) {
                stat(variant);
            }
            return expectedSegments <= 0 || segmentsPresent(masterPlaylistKey, expectedSegments);
        } catch (ErrorResponseException e) {
            if (isMissingObject(e)) {
                log.warn("Durability check failed for {}: {}", masterPlaylistKey, e.getMessage());
                return false;
            }
            throw new VideoExceptions.AssetStoreUnavailable("MinIO refused to confirm " + masterPlaylistKey, e);
        } catch (Exception e) {
            throw new VideoExceptions.AssetStoreUnavailable("Could not reach MinIO to confirm " + masterPlaylistKey, e);
        }
    }

    /**
     * Playlists that exist say nothing about the segments they point at, and a READY video with a
     * hole in it plays up to the hole and then stalls. One listing of the version's prefix is
     * enough to see that segments are missing, where a stat per segment would be hundreds of
     * requests for a ten-minute video. The count is a floor: extra objects are not a defect.
     */
    private boolean segmentsPresent(String masterPlaylistKey, int expectedSegments) throws Exception {
        String prefix = masterPlaylistKey.substring(0, masterPlaylistKey.lastIndexOf('/') + 1);
        int found = 0;
        for (Result<Item> result : minioClient.listObjects(
                ListObjectsArgs.builder().bucket(bucket).prefix(prefix).recursive(true).build())) {
            if (result.get().objectName().endsWith(".ts") && ++found >= expectedSegments) {
                return true;
            }
        }
        log.warn("Durability check failed under {}: {} of {} segments present", prefix, found, expectedSegments);
        return false;
    }

    private static boolean isMissingObject(ErrorResponseException e) {
        return e.errorResponse() != null && MISSING_OBJECT.contains(e.errorResponse().code());
    }

    private void stat(String objectKey) throws Exception {
        minioClient.statObject(StatObjectArgs.builder().bucket(bucket).object(objectKey).build());
    }
}

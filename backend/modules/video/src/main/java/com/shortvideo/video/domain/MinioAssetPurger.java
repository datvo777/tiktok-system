package com.shortvideo.video.domain;

import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.RemoveObjectsArgs;
import io.minio.Result;
import io.minio.messages.DeleteError;
import io.minio.messages.DeleteObject;
import io.minio.messages.Item;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Physically deletes a prefix from MinIO (brief section 7.1, Milestone 6 "superseded
 * processingVersion cleanup"): normally a superseded version's
 * {@code processed/{videoId}/{processingVersion}/} — playlists and every segment — and, for a
 * removed video, its {@code sources/{videoId}/}. Everything under the prefix is listed and
 * bulk-removed, so no segment is left orphaned. An old prefix is never reachable through the media gateway once a
 * newer version is current (brief section 8), so this is storage reclamation,
 * not a playback-safety action.
 */
@Component
class MinioAssetPurger {

    private final MinioClient minioClient;
    private final String bucket;

    MinioAssetPurger(MinioClient minioClient, @Value("${shortvideo.minio.bucket}") String bucket) {
        this.minioClient = minioClient;
        this.bucket = bucket;
    }

    /**
     * Removes every object under {@code prefix}. An empty or already-gone prefix is not an error,
     * which is what makes a retry safe. Anything else is: a failed listing or a per-object delete
     * error throws, so the caller never records a purge that did not happen.
     *
     * @throws AssetPurgeException if the prefix could not be listed or an object could not be deleted
     */
    void purgePrefix(String prefix) {
        List<DeleteObject> objects = new ArrayList<>();
        try {
            for (Result<Item> result : minioClient.listObjects(
                    ListObjectsArgs.builder().bucket(bucket).prefix(prefix).recursive(true).build())) {
                objects.add(new DeleteObject(result.get().objectName()));
            }
        } catch (Exception e) {
            throw new AssetPurgeException("Failed to list prefix " + prefix, e);
        }
        if (objects.isEmpty()) {
            return;
        }
        int failed = 0;
        String firstFailure = null;
        try {
            for (Result<DeleteError> error :
                    minioClient.removeObjects(RemoveObjectsArgs.builder().bucket(bucket).objects(objects).build())) {
                DeleteError e = error.get();
                failed++;
                if (firstFailure == null) {
                    firstFailure = e.objectName() + ": " + e.message();
                }
            }
        } catch (Exception e) {
            throw new AssetPurgeException("Failed to purge prefix " + prefix, e);
        }
        if (failed > 0) {
            throw new AssetPurgeException(
                    failed + " of " + objects.size() + " objects under " + prefix + " were not deleted; first: " + firstFailure,
                    null);
        }
    }

    static class AssetPurgeException extends RuntimeException {
        AssetPurgeException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

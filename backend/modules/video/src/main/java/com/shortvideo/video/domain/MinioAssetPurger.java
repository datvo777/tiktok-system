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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Physically deletes a superseded version's entire processed prefix from MinIO
 * (brief section 7.1, Milestone 6 "superseded processingVersion cleanup"). Every
 * object under {@code processed/{videoId}/{processingVersion}/} — playlists and
 * every segment — is listed and bulk-removed by prefix, so no segment is left
 * orphaned. An old prefix is never reachable through the media gateway once a
 * newer version is current (brief section 8), so this is storage reclamation,
 * not a playback-safety action.
 */
@Component
class MinioAssetPurger {

    private static final Logger log = LoggerFactory.getLogger(MinioAssetPurger.class);

    private final MinioClient minioClient;
    private final String bucket;

    MinioAssetPurger(MinioClient minioClient, @Value("${shortvideo.minio.bucket}") String bucket) {
        this.minioClient = minioClient;
        this.bucket = bucket;
    }

    /** Best-effort: an empty or already-gone prefix is not an error. */
    void purgePrefix(String prefix) {
        List<DeleteObject> objects = new ArrayList<>();
        try {
            for (Result<Item> result : minioClient.listObjects(
                    ListObjectsArgs.builder().bucket(bucket).prefix(prefix).recursive(true).build())) {
                objects.add(new DeleteObject(result.get().objectName()));
            }
        } catch (Exception e) {
            log.warn("Failed to list superseded prefix {}: {}", prefix, e.getMessage());
            return;
        }
        if (objects.isEmpty()) {
            return;
        }
        try {
            for (Result<DeleteError> error :
                    minioClient.removeObjects(RemoveObjectsArgs.builder().bucket(bucket).objects(objects).build())) {
                DeleteError e = error.get();
                log.warn("Failed to purge {}: {}", e.objectName(), e.message());
            }
        } catch (Exception e) {
            log.warn("Failed to purge superseded prefix {}: {}", prefix, e.getMessage());
        }
    }
}

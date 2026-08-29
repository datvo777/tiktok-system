package com.shortvideo.video.domain;

import io.minio.MinioClient;
import io.minio.StatObjectArgs;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Durability check (brief section 11.1, step 3): confirms the assets the worker
 * declared complete actually exist in MinIO before the video is marked DURABLE.
 * This is the local simulation of durability (Rule 14) — it proves the objects are
 * present, not that they are replicated anywhere.
 */
@Component
class MinioAssetVerifier {

    private static final Logger log = LoggerFactory.getLogger(MinioAssetVerifier.class);

    private final MinioClient minioClient;
    private final String bucket;

    MinioAssetVerifier(MinioClient minioClient, @Value("${shortvideo.minio.bucket}") String bucket) {
        this.minioClient = minioClient;
        this.bucket = bucket;
    }

    boolean verify(String masterPlaylistKey, List<String> variantPlaylistKeys) {
        if (masterPlaylistKey == null || masterPlaylistKey.isBlank()) {
            return false;
        }
        try {
            stat(masterPlaylistKey);
            for (String variant : variantPlaylistKeys) {
                stat(variant);
            }
            return true;
        } catch (Exception e) {
            log.warn("Durability check failed for {}: {}", masterPlaylistKey, e.getMessage());
            return false;
        }
    }

    private void stat(String objectKey) throws Exception {
        minioClient.statObject(StatObjectArgs.builder().bucket(bucket).object(objectKey).build());
    }
}

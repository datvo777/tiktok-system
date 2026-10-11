package com.shortvideo.video.domain;

import io.minio.MinioClient;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
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
 * present, not that they are replicated anywhere.
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
     * @return true if every object exists, false if the master playlist is not given or any object is missing
     * @throws VideoExceptions.AssetStoreUnavailable if MinIO could not answer
     */
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

    private static boolean isMissingObject(ErrorResponseException e) {
        return e.errorResponse() != null && MISSING_OBJECT.contains(e.errorResponse().code());
    }

    private void stat(String objectKey) throws Exception {
        minioClient.statObject(StatObjectArgs.builder().bucket(bucket).object(objectKey).build());
    }
}

package com.shortvideo.worker;

import io.minio.CopySource;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.minio.GetObjectArgs;
import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectsArgs;
import io.minio.Result;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import io.minio.messages.DeleteError;
import io.minio.messages.DeleteObject;
import io.minio.messages.Item;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Thin MinIO wrapper for the worker's stage-then-promote upload flow.
 *
 * <p>Each call is tried up to {@value #ATTEMPTS} times with a short backoff, so a MinIO that
 * blips for a second or two does not fail a transcode that took minutes. An answer from MinIO
 * that will not change (the object does not exist, access is denied, a bad request) is not retried.
 */
@Component
class MinioObjectStore {

    private static final Logger log = LoggerFactory.getLogger(MinioObjectStore.class);
    private static final int ATTEMPTS = 3;
    private static final Set<String> MISSING_OBJECT = Set.of("NoSuchKey", "NoSuchObject");

    private final MinioClient client;
    private final String bucket;
    private final Duration retryDelay;
    private final Counter retries;

    @Autowired
    MinioObjectStore(
            MinioClient client,
            com.shortvideo.worker.config.MinioConfig.MinioProperties properties,
            MeterRegistry meters) {
        this(client, properties, Duration.ofMillis(500), meters);
    }

    MinioObjectStore(
            MinioClient client,
            com.shortvideo.worker.config.MinioConfig.MinioProperties properties,
            Duration retryDelay,
            MeterRegistry meters) {
        this.client = client;
        this.bucket = properties.getBucket();
        this.retryDelay = retryDelay;
        // Each retry hides a MinIO hiccup from the job, so a rising count is the early sign of trouble.
        this.retries = Counter.builder("worker.minio.retries").register(meters);
    }

    private interface Call<T> {
        T run() throws Exception;
    }

    private <T> T withRetry(String what, Call<T> call) throws Exception {
        for (int attempt = 1; ; attempt++) {
            try {
                return call.run();
            } catch (Exception e) {
                if (attempt >= ATTEMPTS || !worthRetrying(e)) {
                    throw e;
                }
                retries.increment();
                long delayMillis = retryDelay.toMillis() << (attempt - 1);
                log.warn("MinIO {} failed (attempt {} of {}), retrying in {} ms: {}",
                        what, attempt, ATTEMPTS, delayMillis, e.toString());
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    /** A server error or a connection problem may pass; a 4xx answer is MinIO's final word. */
    private static boolean worthRetrying(Exception e) {
        if (e instanceof ErrorResponseException response) {
            return response.response() != null && response.response().code() >= 500;
        }
        return true;
    }

    /**
     * @throws TranscodeFailedException {@code TERMINAL} if the source object does not exist: no
     *     number of retries will make it appear
     */
    void downloadTo(String objectKey, Path destination) throws Exception {
        try {
            withRetry("download of " + objectKey, () -> {
                try (InputStream in = client.getObject(GetObjectArgs.builder().bucket(bucket).object(objectKey).build())) {
                    Files.copy(in, destination, StandardCopyOption.REPLACE_EXISTING);
                }
                return null;
            });
        } catch (ErrorResponseException e) {
            if (e.errorResponse() != null && MISSING_OBJECT.contains(e.errorResponse().code())) {
                throw new TranscodeFailedException("TERMINAL", "Source object does not exist: " + objectKey);
            }
            throw e;
        }
    }

    void upload(String objectKey, Path source, String contentType) throws Exception {
        withRetry("upload of " + objectKey, () -> {
            try (InputStream in = Files.newInputStream(source)) {
                client.putObject(PutObjectArgs.builder()
                        .bucket(bucket)
                        .object(objectKey)
                        .stream(in, Files.size(source), -1)
                        .contentType(contentType)
                        .build());
            }
            return null;
        });
    }

    boolean exists(String objectKey) {
        try {
            client.statObject(StatObjectArgs.builder().bucket(bucket).object(objectKey).build());
            return true;
        } catch (ErrorResponseException notFound) {
            return false;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to stat " + objectKey, e);
        }
    }

    /** Server-side copy: how staged processing-temp objects are promoted to processed/. */
    void copy(String sourceKey, String destinationKey) throws Exception {
        withRetry("copy to " + destinationKey, () -> client.copyObject(io.minio.CopyObjectArgs.builder()
                .bucket(bucket)
                .object(destinationKey)
                .source(CopySource.builder().bucket(bucket).object(sourceKey).build())
                .build()));
    }

    List<String> listKeysUnder(String prefix) {
        return listKeysUnder(prefix, null);
    }

    /** Keys under {@code prefix} last modified before {@code olderThan}; all of them when that is null. */
    List<String> listKeysUnder(String prefix, Instant olderThan) {
        try {
            return withRetry("listing of " + prefix, () -> {
                List<String> keys = new ArrayList<>();
                for (Result<Item> result : client.listObjects(
                        ListObjectsArgs.builder().bucket(bucket).prefix(prefix).recursive(true).build())) {
                    Item item = result.get();
                    if (olderThan == null || item.lastModified().toInstant().isBefore(olderThan)) {
                        keys.add(item.objectName());
                    }
                }
                return keys;
            });
        } catch (Exception e) {
            throw new IllegalStateException("Failed to list objects under " + prefix, e);
        }
    }

    /** Removes everything under {@code prefix}; see {@link #deleteAll}. */
    int deleteAllUnder(String prefix) {
        return deleteAll(listKeysUnder(prefix));
    }

    /**
     * Removes the objects in batched requests (S3 takes up to a thousand per call), where a request
     * per object cost a round trip, and up to three tries, for every segment of every rung. A key that
     * cannot be removed does not stop the others: MinIO reports each failure and carries on.
     *
     * @return how many objects could not be removed; zero when all are gone. A missing key is not a
     *     failure. The whole call failing counts every key as not removed.
     */
    int deleteAll(List<String> keys) {
        if (keys.isEmpty()) {
            return 0;
        }
        List<DeleteObject> objects = keys.stream().map(DeleteObject::new).toList();
        try {
            return withRetry("batch delete of " + objects.size() + " objects", () -> {
                int failed = 0;
                // The results are only the failures; a clean batch yields none.
                for (Result<DeleteError> result : client.removeObjects(
                        RemoveObjectsArgs.builder().bucket(bucket).objects(objects).build())) {
                    DeleteError error = result.get();
                    failed++;
                    log.warn("MinIO did not delete {}: {}", error.objectName(), error.message());
                }
                return failed;
            });
        } catch (Exception e) {
            log.warn("Batch delete of {} objects failed: {}", objects.size(), e.toString());
            return objects.size();
        }
    }
}

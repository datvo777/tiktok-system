package com.shortvideo.worker;

import io.minio.CopySource;
import io.minio.GetObjectArgs;
import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.Result;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import io.minio.messages.Item;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/** Thin MinIO wrapper for the worker's stage-then-promote upload flow. */
@Component
class MinioObjectStore {

    private final MinioClient client;
    private final String bucket;

    MinioObjectStore(MinioClient client, com.shortvideo.worker.config.MinioConfig.MinioProperties properties) {
        this.client = client;
        this.bucket = properties.getBucket();
    }

    void downloadTo(String objectKey, Path destination) throws Exception {
        try (InputStream in = client.getObject(GetObjectArgs.builder().bucket(bucket).object(objectKey).build())) {
            Files.copy(in, destination);
        }
    }

    void upload(String objectKey, Path source, String contentType) throws Exception {
        try (InputStream in = Files.newInputStream(source)) {
            client.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .stream(in, Files.size(source), -1)
                    .contentType(contentType)
                    .build());
        }
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
        client.copyObject(io.minio.CopyObjectArgs.builder()
                .bucket(bucket)
                .object(destinationKey)
                .source(CopySource.builder().bucket(bucket).object(sourceKey).build())
                .build());
    }

    List<String> listKeysUnder(String prefix) {
        List<String> keys = new ArrayList<>();
        for (Result<Item> result :
                client.listObjects(ListObjectsArgs.builder().bucket(bucket).prefix(prefix).recursive(true).build())) {
            try {
                keys.add(result.get().objectName());
            } catch (Exception e) {
                throw new IllegalStateException("Failed to list objects under " + prefix, e);
            }
        }
        return keys;
    }

    void delete(String objectKey) {
        try {
            client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(objectKey).build());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to delete " + objectKey, e);
        }
    }
}

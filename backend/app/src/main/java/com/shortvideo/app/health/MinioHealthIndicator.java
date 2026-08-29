package com.shortvideo.app.health;

import com.shortvideo.app.config.MinioConfig.MinioProperties;
import io.minio.BucketExistsArgs;
import io.minio.MinioClient;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/** Readiness contributor: the bucket must exist before uploads can be accepted. */
@Component("minio")
public class MinioHealthIndicator implements HealthIndicator {

    private final MinioClient client;
    private final MinioProperties properties;

    public MinioHealthIndicator(MinioClient client, MinioProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    @Override
    public Health health() {
        try {
            boolean exists = client.bucketExists(
                    BucketExistsArgs.builder().bucket(properties.getBucket()).build());
            if (!exists) {
                return Health.down()
                        .withDetail("bucket", properties.getBucket())
                        .withDetail("reason", "bucket missing — run the minio-init compose service")
                        .build();
            }
            return Health.up().withDetail("bucket", properties.getBucket()).build();
        } catch (Exception e) {
            return Health.down(e).withDetail("endpoint", properties.getEndpoint()).build();
        }
    }
}

package com.shortvideo.app.config;

import io.minio.MinioClient;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MinioConfig {

    @Bean
    @ConfigurationProperties(prefix = "shortvideo.minio")
    public MinioProperties minioProperties() {
        return new MinioProperties();
    }

    @Bean
    public MinioClient minioClient(MinioProperties properties) {
        MinioClient client = MinioClient.builder()
                .endpoint(properties.getEndpoint())
                // Without an explicit region the SDK asks the server for the bucket's region
                // the first time it signs anything, and createSession signs inside a
                // transaction. Fixing it here makes signing purely local.
                .region(properties.getRegion())
                .credentials(properties.getAccessKey(), properties.getSecretKey())
                .build();
        // The SDK defaults to 5 minutes for connect, write and read. complete() calls the
        // store inside a DB transaction, so a hung MinIO would pin a thread and a
        // connection per request for that long.
        client.setTimeout(
                properties.getConnectTimeout().toMillis(),
                properties.getWriteTimeout().toMillis(),
                properties.getReadTimeout().toMillis());
        return client;
    }

    public static class MinioProperties {
        private String endpoint = "http://localhost:9000";
        private String region = "us-east-1";
        private String accessKey;
        private String secretKey;
        private String bucket = "short-video";
        // Read must outlast a server-side copy of the largest allowed upload.
        private Duration connectTimeout = Duration.ofSeconds(3);
        private Duration writeTimeout = Duration.ofSeconds(30);
        private Duration readTimeout = Duration.ofSeconds(30);

        public String getEndpoint() { return endpoint; }
        public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
        public String getRegion() { return region; }
        public void setRegion(String region) { this.region = region; }
        public String getAccessKey() { return accessKey; }
        public void setAccessKey(String accessKey) { this.accessKey = accessKey; }
        public String getSecretKey() { return secretKey; }
        public void setSecretKey(String secretKey) { this.secretKey = secretKey; }
        public String getBucket() { return bucket; }
        public void setBucket(String bucket) { this.bucket = bucket; }
        public Duration getConnectTimeout() { return connectTimeout; }
        public void setConnectTimeout(Duration connectTimeout) { this.connectTimeout = connectTimeout; }
        public Duration getWriteTimeout() { return writeTimeout; }
        public void setWriteTimeout(Duration writeTimeout) { this.writeTimeout = writeTimeout; }
        public Duration getReadTimeout() { return readTimeout; }
        public void setReadTimeout(Duration readTimeout) { this.readTimeout = readTimeout; }
    }
}

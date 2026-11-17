package com.shortvideo.app.config;

import io.minio.MinioClient;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import okhttp3.ConnectionPool;
import okhttp3.Dispatcher;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
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
        return MinioClient.builder()
                .endpoint(properties.getEndpoint())
                // Without an explicit region the SDK asks the server for the bucket's region
                // the first time it signs anything. Fixing it here makes signing purely local.
                .region(properties.getRegion())
                .credentials(properties.getAccessKey(), properties.getSecretKey())
                .httpClient(httpClient(properties))
                .build();
    }

    /**
     * The SDK's own default client is {@code new OkHttpClient()} plus 5-minute timeouts, which is
     * wrong for this service in two ways, so it is built here instead.
     *
     * <p>The blocking {@code MinioClient} is a wrapper over the async one, and every call goes
     * through {@code Call.enqueue}. That puts all of them under OkHttp's {@link Dispatcher}, whose
     * default is <b>5 concurrent requests per host</b>. All traffic goes to one MinIO host, so five
     * slow calls (a server-side copy of a large upload holds its slot for the whole copy) would
     * make every other call wait unseen in the dispatcher queue: {@code stat}s in {@code complete},
     * playback range reads, cleanup. The read timeout only starts once a request is running, so a
     * queued call never times out and nothing reports it. The limits are raised to cover the real
     * demand (concurrent streams plus uploads being completed) and made configurable.
     *
     * <p>The connection pool is sized to match, so a burst does not leave only 5 warm connections
     * and pay a fresh TCP connect for the rest. HTTP/1.1 only, as the SDK's default.
     */
    static OkHttpClient httpClient(MinioProperties properties) {
        Dispatcher dispatcher = new Dispatcher();
        dispatcher.setMaxRequests(properties.getMaxConcurrentRequests());
        dispatcher.setMaxRequestsPerHost(properties.getMaxConcurrentRequests());
        return new OkHttpClient.Builder()
                .dispatcher(dispatcher)
                .connectionPool(new ConnectionPool(
                        properties.getMaxIdleConnections(),
                        properties.getIdleConnectionKeepAlive().toMillis(),
                        TimeUnit.MILLISECONDS))
                // A hung MinIO must fail fast (503) instead of pinning a request thread per call.
                // Read must outlast a server-side copy of the largest allowed upload.
                .connectTimeout(properties.getConnectTimeout())
                .writeTimeout(properties.getWriteTimeout())
                .readTimeout(properties.getReadTimeout())
                .protocols(List.of(Protocol.HTTP_1_1))
                .build();
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
        // Above the 32 concurrent media streams plus uploads being completed and cleanup jobs.
        private int maxConcurrentRequests = 64;
        private int maxIdleConnections = 32;
        private Duration idleConnectionKeepAlive = Duration.ofMinutes(5);

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
        public int getMaxConcurrentRequests() { return maxConcurrentRequests; }
        public void setMaxConcurrentRequests(int maxConcurrentRequests) { this.maxConcurrentRequests = maxConcurrentRequests; }
        public int getMaxIdleConnections() { return maxIdleConnections; }
        public void setMaxIdleConnections(int maxIdleConnections) { this.maxIdleConnections = maxIdleConnections; }
        public Duration getIdleConnectionKeepAlive() { return idleConnectionKeepAlive; }
        public void setIdleConnectionKeepAlive(Duration idleConnectionKeepAlive) { this.idleConnectionKeepAlive = idleConnectionKeepAlive; }
        public Duration getConnectTimeout() { return connectTimeout; }
        public void setConnectTimeout(Duration connectTimeout) { this.connectTimeout = connectTimeout; }
        public Duration getWriteTimeout() { return writeTimeout; }
        public void setWriteTimeout(Duration writeTimeout) { this.writeTimeout = writeTimeout; }
        public Duration getReadTimeout() { return readTimeout; }
        public void setReadTimeout(Duration readTimeout) { this.readTimeout = readTimeout; }
    }
}

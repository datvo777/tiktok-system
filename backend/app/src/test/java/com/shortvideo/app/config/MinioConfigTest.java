package com.shortvideo.app.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import org.junit.jupiter.api.Test;

class MinioConfigTest {

    @Test
    void raisesTheDispatcherLimitsTheSdkDefaultsWouldQueueBehind() {
        var properties = new MinioConfig.MinioProperties();
        properties.setMaxConcurrentRequests(40);

        OkHttpClient client = MinioConfig.httpClient(properties);

        // OkHttp's own default is 5 per host; every MinioClient call goes through the dispatcher.
        assertThat(client.dispatcher().getMaxRequests()).isEqualTo(40);
        assertThat(client.dispatcher().getMaxRequestsPerHost()).isEqualTo(40);
    }

    @Test
    void keepsTheConfiguredTimeoutsAndProtocol() {
        var properties = new MinioConfig.MinioProperties();

        OkHttpClient client = MinioConfig.httpClient(properties);

        assertThat(client.connectTimeoutMillis()).isEqualTo(TimeUnit.SECONDS.toMillis(3));
        assertThat(client.writeTimeoutMillis()).isEqualTo(TimeUnit.SECONDS.toMillis(30));
        assertThat(client.readTimeoutMillis()).isEqualTo(TimeUnit.SECONDS.toMillis(30));
        assertThat(client.protocols()).containsExactly(Protocol.HTTP_1_1);
    }
}

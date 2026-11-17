package com.shortvideo.app.health;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.DescribeClusterOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.stereotype.Component;

/**
 * Readiness contributor. Confirms this process can reach the broker through the
 * HOST listener — the failure mode that otherwise shows up much later as a
 * silently stalled outbox relay.
 *
 * <p>The AdminClient is created here rather than by Spring, so it is given the same
 * connection and security settings as the application's other clients
 * ({@link KafkaProperties#buildAdminProperties}). A client without them would be refused by a
 * SASL_SSL broker and report DOWN while the rest of the application works. Against a broker
 * with ACLs the principal needs {@code Describe} on the cluster for this to be UP.
 */
@Component("kafka")
public class KafkaHealthIndicator implements HealthIndicator {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final Map<String, Object> adminConfig;
    private final String bootstrapServers;

    public KafkaHealthIndicator(KafkaProperties kafkaProperties, ObjectProvider<SslBundles> sslBundles) {
        Map<String, Object> config = kafkaProperties.buildAdminProperties(sslBundles.getIfAvailable());
        config.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, (int) TIMEOUT.toMillis());
        config.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, (int) TIMEOUT.toMillis());
        this.adminConfig = config;
        this.bootstrapServers = String.join(",", kafkaProperties.getBootstrapServers());
    }

    @Override
    public Health health() {
        try (AdminClient admin = AdminClient.create(adminConfig)) {
            var cluster = admin.describeCluster(
                    new DescribeClusterOptions().timeoutMs((int) TIMEOUT.toMillis()));
            int nodes = cluster.nodes().get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS).size();
            String clusterId = cluster.clusterId().get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            return Health.up()
                    .withDetail("clusterId", clusterId)
                    .withDetail("nodes", nodes)
                    .withDetail("bootstrapServers", bootstrapServers)
                    .build();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Health.down(e).build();
        } catch (Exception e) {
            return Health.down(e).withDetail("bootstrapServers", bootstrapServers).build();
        }
    }
}

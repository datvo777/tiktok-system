package com.shortvideo.app.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.config.SaslConfigs;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.autoconfigure.ssl.SslAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.ProducerFactory;

/**
 * The producer is built by hand, which switches off Boot's auto-configured one. This pins that
 * connection and security settings still reach it, so turning on SASL_SSL in the environment
 * cannot silently leave the outbox relay talking plaintext.
 */
class KafkaConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(KafkaAutoConfiguration.class, SslAutoConfiguration.class))
            .withUserConfiguration(KafkaConfig.class);

    @Test
    void securitySettingsFromSpringKafkaPropertiesReachTheProducer() {
        runner.withPropertyValues(
                        "spring.kafka.bootstrap-servers=broker:29092",
                        "spring.kafka.security.protocol=SASL_SSL",
                        "spring.kafka.properties.sasl.mechanism=SCRAM-SHA-512",
                        "spring.kafka.properties.sasl.jaas.config=org.apache.kafka.common.security.scram.ScramLoginModule required username=\"backend\" password=\"pw\";")
                .run(context -> {
                    var config = producerConfig(context.getBean(ProducerFactory.class));
                    assertThat(config)
                            .containsEntry(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, "SASL_SSL")
                            .containsEntry(SaslConfigs.SASL_MECHANISM, "SCRAM-SHA-512")
                            .containsKey(SaslConfigs.SASL_JAAS_CONFIG);
                    assertThat(config.get(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG).toString()).contains("broker:29092");
                });
    }

    @Test
    void idempotenceSettingsAreNotLostToTheSharedProperties() {
        runner.withPropertyValues("spring.kafka.bootstrap-servers=broker:29092").run(context -> {
            var config = producerConfig(context.getBean(ProducerFactory.class));
            assertThat(config)
                    .containsEntry(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true)
                    .containsEntry(ProducerConfig.ACKS_CONFIG, "all")
                    .doesNotContainKey(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG);
        });
    }

    private static java.util.Map<String, Object> producerConfig(ProducerFactory<?, ?> factory) {
        return ((DefaultKafkaProducerFactory<?, ?>) factory).getConfigurationProperties();
    }
}

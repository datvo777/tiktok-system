package com.shortvideo.shared.outbox;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableConfigurationProperties(OutboxProperties.class)
@EnableScheduling
public class OutboxConfiguration {

    /** One relay process locally. Disable in tests that assert on raw outbox rows. */
    @Bean
    @ConditionalOnProperty(prefix = "shortvideo.outbox", name = "enabled", havingValue = "true", matchIfMissing = true)
    public OutboxRelay outboxRelay(
            OutboxRepository repository,
            KafkaTemplate<String, String> kafka,
            TopicResolver topicResolver,
            OutboxProperties properties,
            MeterRegistry meters) {
        return new OutboxRelay(repository, kafka, topicResolver, properties, meters);
    }
}

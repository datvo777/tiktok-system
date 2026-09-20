package com.shortvideo.app.health;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Subscribes to every {@code *.DLT} topic across the cluster (backend and
 * media-worker both publish to one via {@code DeadLetterPublishingRecoverer}) so a
 * record reaching a dead letter always trips a counter and a log line, never just
 * sits there until someone happens to check Kafka UI.
 */
@Component
class DeadLetterAlertListener {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterAlertListener.class);

    private final Counter deadLettered;

    DeadLetterAlertListener(MeterRegistry meters) {
        this.deadLettered = Counter.builder("kafka.consumer.dead_lettered").register(meters);
    }

    @KafkaListener(topicPattern = ".*\\.DLT", groupId = "dlq-alert-sink")
    public void onDeadLetter(ConsumerRecord<String, String> record) {
        deadLettered.increment();
        log.error("Record moved to DLT: topic={} key={} headers={}", record.topic(), record.key(), record.headers());
    }
}

package com.shortvideo.app.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.JsonParseException;
import com.shortvideo.video.domain.VideoExceptions;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.kafka.listener.MessageListenerContainer;

/** When a record that a listener could not apply stops being retried and is dead-lettered. */
class KafkaErrorHandlerTest {

    private KafkaTemplate<String, String> template;
    private DefaultErrorHandler handler;
    private final Consumer<?, ?> consumer = mock(Consumer.class);
    private final MessageListenerContainer container = mock(MessageListenerContainer.class);

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        template = mock(KafkaTemplate.class);
        when(template.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));
        handler = new KafkaConfig().kafkaErrorHandler(template);
    }

    private boolean deadLettered() {
        return mockingDetails(template).getInvocations().stream()
                .anyMatch(invocation -> invocation.getMethod().getName().equals("send"));
    }

    /** Deliveries it takes before the record is sent to the DLT; gives up after twenty. */
    private int deliveriesUntilDeadLettered(Throwable cause) {
        ConsumerRecord<String, String> record = new ConsumerRecord<>("media.results.v1", 0, 5L, "v1", "{}");
        int deliveries = 0;
        while (deliveries < 20 && !deadLettered()) {
            try {
                handler.handleRemaining(
                        new ListenerExecutionFailedException("failed", new RuntimeException(cause)),
                        List.of(record), consumer, container);
            } catch (RuntimeException e) {
                if (!e.getClass().getSimpleName().equals("RecordInRetryException")) {
                    throw e;
                }
            }
            deliveries++;
        }
        return deliveries;
    }

    @Test
    void anOrdinaryFailureIsRetriedFiveTimesBeforeTheDeadLetterTopic() {
        assertThat(deliveriesUntilDeadLettered(new IllegalStateException("bug"))).isEqualTo(6);
    }

    @Test
    void aMalformedRecordIsNotRetriedAtAll() {
        assertThat(deliveriesUntilDeadLettered(new JsonParseException(null, "bad json"))).isEqualTo(1);
    }

    @Test
    void anObjectStoreThatCannotBeAskedGetsAMuchLongerRun() {
        // Nine retries: the result is good, and the store will be back.
        assertThat(deliveriesUntilDeadLettered(
                        new VideoExceptions.AssetStoreUnavailable("MinIO down", new RuntimeException())))
                .isEqualTo(10);
    }

    @Test
    void theLongerRunStaysInsideTheConsumersPollInterval() {
        var backoff = KafkaConfig.storeOutageBackoff().start();
        long totalMillis = 0;
        for (long wait = backoff.nextBackOff(); wait != org.springframework.util.backoff.BackOffExecution.STOP;
                wait = backoff.nextBackOff()) {
            totalMillis += wait;
        }

        // The default max.poll.interval.ms is five minutes.
        assertThat(totalMillis).isBetween(120_000L, 200_000L);
    }
}

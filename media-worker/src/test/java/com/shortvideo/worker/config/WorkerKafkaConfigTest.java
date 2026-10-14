package com.shortvideo.worker.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.JsonParseException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.kafka.listener.MessageListenerContainer;

/**
 * What happens to a {@code media.jobs.v1} record the listener could not handle. A malformed command
 * has no other safety net (it fails before the job can report a result), so losing it here leaves
 * its video in TRANSCODING with nothing to say why.
 */
class WorkerKafkaConfigTest {

    private static final String TOPIC = "media.jobs.v1";

    private KafkaTemplate<String, String> template;
    private DefaultErrorHandler handler;
    private final Consumer<?, ?> consumer = mock(Consumer.class);
    private final MessageListenerContainer container = mock(MessageListenerContainer.class);

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        template = mock(KafkaTemplate.class);
        when(template.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));
        handler = new WorkerKafkaConfig().kafkaErrorHandler(template);
    }

    @Test
    void aMalformedCommandGoesStraightToTheDeadLetterTopicWithoutRetries() {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(TOPIC, 2, 7L, "video-1", "{not json");

        deliver(record, new JsonParseException(null, "bad json"));

        ProducerRecord<String, String> dead = sentToDeadLetter();
        assertThat(dead.topic()).isEqualTo(TOPIC + ".DLT");
        assertThat(dead.key()).isEqualTo("video-1");
        assertThat(dead.value()).isEqualTo("{not json");
        assertThat(dead.headers().lastHeader("kafka_dlt-original-topic")).isNotNull();
        assertThat(dead.headers().lastHeader("kafka_dlt-exception-fqcn")).isNotNull();
    }

    @Test
    void anUnexpectedFailureIsRetriedAndOnlyDeadLetteredOnceTheBudgetIsSpent() {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(TOPIC, 0, 3L, "video-2", "{}");
        RuntimeException cause = new IllegalStateException("handler blew up");

        int deliveries = 0;
        while (deliveries < 10 && !deadLettered()) {
            deliver(record, cause);
            deliveries++;
        }

        // The first delivery plus five retries, and no record sent to the DLT before the last.
        assertThat(deliveries).isEqualTo(6);
        assertThat(sentToDeadLetter().topic()).isEqualTo(TOPIC + ".DLT");
    }

    private boolean deadLettered() {
        return !org.mockito.Mockito.mockingDetails(template).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("send"))
                .toList()
                .isEmpty();
    }

    /** One failed delivery. While a record is still being retried the handler says so by throwing. */
    private void deliver(ConsumerRecord<String, String> record, Throwable cause) {
        try {
            handler.handleRemaining(failure(cause), List.of(record), consumer, container);
        } catch (RuntimeException e) {
            // Expected while retrying: the record was seeked back to and will be delivered again.
            // The exception type is package-private, so it is recognised by name; anything else is real.
            if (!e.getClass().getSimpleName().equals("RecordInRetryException")) {
                throw e;
            }
        }
    }

    private ProducerRecord<String, String> sentToDeadLetter() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<ProducerRecord<String, String>> sent = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(template, times(1)).send(sent.capture());
        return sent.getValue();
    }

    private static ListenerExecutionFailedException failure(Throwable cause) {
        return new ListenerExecutionFailedException("listener failed", cause);
    }
}

package com.shortvideo.notification.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.shortvideo.shared.security.SessionRevalidator;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Bookkeeping only. An emitter that no request has picked up yet never runs its completion
 * callbacks, so that the evicted and revalidated streams really end is covered over a real
 * connection by RealtimeStreamIT.
 */
class SseRegistryTest {

    private final RealtimeProperties properties = new RealtimeProperties();
    private final SessionRevalidator revalidator = mock(SessionRevalidator.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private SseRegistry registry;

    @BeforeEach
    void setUp() {
        properties.setMaxConnectionsPerAccount(3);
        properties.setMaxConnections(5);
        when(revalidator.isStillEntitled(anyString(), any())).thenReturn(true);
        registry = new SseRegistry(properties, revalidator, meters);
    }

    private SseEmitter accept(String account) {
        return ((SseRegistry.Opened.Accepted) registry.open(account, Instant.now())).emitter();
    }

    @Test
    void anAccountKeepsItsNewestThreeStreams() {
        accept("a");
        accept("a");
        accept("a");
        accept("a");

        assertThat(registry.openForAccount("a")).isEqualTo(3);
        assertThat(registry.openConnections()).isEqualTo(3);
    }

    @Test
    void theInstanceRefusesPastItsLimitAndSaysWhenToComeBack() {
        accept("a");
        accept("a");
        accept("a");
        accept("b");
        accept("b");

        var refused = registry.open("c", Instant.now());

        assertThat(refused).isInstanceOf(SseRegistry.Opened.Rejected.class);
        assertThat(((SseRegistry.Opened.Rejected) refused).retryAfter()).isEqualTo(properties.getRetryAfter());
        assertThat(registry.openConnections()).isEqualTo(5);
        assertThat(meters.counter("realtime.rejected").count()).isEqualTo(1);
    }

    @Test
    void aStreamThatLostItsStandingIsClosedAndForgotten() {
        accept("a");
        accept("b");
        // Matches by account: only a's stream is let go.
        when(revalidator.isStillEntitled(org.mockito.ArgumentMatchers.eq("a"), any())).thenReturn(false);

        registry.revalidate();

        assertThat(registry.openForAccount("a")).isZero();
        assertThat(registry.openForAccount("b")).isEqualTo(1);
        assertThat(meters.counter("realtime.revalidation.closed").count()).isEqualTo(1);
    }

    @Test
    void shuttingDownEndsEveryStream() {
        for (String account : new String[] {"a", "b", "c"}) {
            accept(account);
        }

        registry.closeAll();

        assertThat(registry.openConnections()).isZero();
        assertThat(registry.openForAccount("a")).isZero();
    }

    @Test
    void publishingToAnAccountWithNoStreamIsANoOp() {
        registry.publish("nobody", "X", null, Instant.now());

        assertThat(meters.counter("realtime.events.sent").count()).isZero();
    }
}

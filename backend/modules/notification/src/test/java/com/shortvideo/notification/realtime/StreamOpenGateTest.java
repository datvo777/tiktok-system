package com.shortvideo.notification.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StreamOpenGateTest {

    private final RealtimeProperties properties = new RealtimeProperties();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final AtomicLong clock = new AtomicLong(1_000_000_000_000L);
    private StreamOpenGate gate;

    @BeforeEach
    void setUp() {
        properties.setMaxOpensPerSecond(10);
        properties.setOpenBurst(20);
        properties.setMaxConcurrentOpens(100);
        gate = new StreamOpenGate(properties, meters, clock::get);
    }

    private void passSeconds(double s) {
        clock.addAndGet((long) (s * 1e9));
    }

    @Test
    void admitsABurstThenRefusesUntilTheBucketRefills() {
        int admitted = 0;
        for (int i = 0; i < 30; i++) {
            var entry = gate.tryEnter();
            if (entry instanceof StreamOpenGate.Entry.Admitted a) {
                admitted++;
                a.close();
            }
        }
        assertThat(admitted).isEqualTo(20);

        passSeconds(0.5); // 5 tokens at 10/s
        int afterRefill = 0;
        for (int i = 0; i < 10; i++) {
            if (gate.tryEnter() instanceof StreamOpenGate.Entry.Admitted a) {
                afterRefill++;
                a.close();
            }
        }
        assertThat(afterRefill).isEqualTo(5);
    }

    @Test
    void theBucketNeverHoldsMoreThanTheBurst() {
        passSeconds(3600);

        int admitted = 0;
        for (int i = 0; i < 100; i++) {
            if (gate.tryEnter() instanceof StreamOpenGate.Entry.Admitted a) {
                admitted++;
                a.close();
            }
        }

        assertThat(admitted).isEqualTo(20);
    }

    @Test
    void onlyAFewOpensMayBeAuthenticatingAtOnce() {
        properties.setMaxConcurrentOpens(3);
        properties.setOpenBurst(1000);
        gate = new StreamOpenGate(properties, meters, clock::get);
        List<StreamOpenGate.Entry.Admitted> held = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            held.add((StreamOpenGate.Entry.Admitted) gate.tryEnter());
        }

        var fourth = gate.tryEnter();

        assertThat(fourth).isInstanceOf(StreamOpenGate.Entry.Refused.class);
        assertThat(((StreamOpenGate.Entry.Refused) fourth).reason()).isEqualTo(StreamOpenGate.Refusal.CONCURRENCY);
        held.get(0).close();
        assertThat(gate.tryEnter()).isInstanceOf(StreamOpenGate.Entry.Admitted.class);
    }

    @Test
    void closingTwiceReleasesOnlyOnePermit() {
        properties.setMaxConcurrentOpens(1);
        properties.setOpenBurst(1000);
        gate = new StreamOpenGate(properties, meters, clock::get);
        var first = (StreamOpenGate.Entry.Admitted) gate.tryEnter();

        first.close();
        first.close();

        var a = (StreamOpenGate.Entry.Admitted) gate.tryEnter();
        assertThat(gate.tryEnter()).isInstanceOf(StreamOpenGate.Entry.Refused.class);
        a.close();
    }

    @Test
    void aRefusedClientIsToldToComeBackWithinTheWindowAndNeverImmediately() {
        properties.setOpenBurst(1);
        gate = new StreamOpenGate(properties, meters, clock::get);
        gate.tryEnter(); // takes the one token

        for (int i = 0; i < 200; i++) {
            var refused = (StreamOpenGate.Entry.Refused) gate.tryEnter();
            assertThat(refused.retryAfter().toSeconds()).isBetween(1L, properties.getRetryAfterMax().toSeconds());
        }
    }

    @Test
    void theRetryWindowWidensAsRefusalsPileUp() {
        properties.setOpenBurst(1);
        properties.setMaxOpensPerSecond(10);
        gate = new StreamOpenGate(properties, meters, clock::get);
        gate.tryEnter();

        long first = 0;
        for (int i = 0; i < 5; i++) {
            first = Math.max(first, ((StreamOpenGate.Entry.Refused) gate.tryEnter()).retryAfter().toSeconds());
        }
        long late = 0;
        for (int i = 0; i < 400; i++) {
            late = Math.max(late, ((StreamOpenGate.Entry.Refused) gate.tryEnter()).retryAfter().toSeconds());
        }

        assertThat(late).isGreaterThan(first);
    }

    @Test
    void aRateOfZeroTurnsTheBucketOff() {
        properties.setMaxOpensPerSecond(0);
        properties.setMaxConcurrentOpens(1000);
        gate = new StreamOpenGate(properties, meters, clock::get);

        for (int i = 0; i < 500; i++) {
            var entry = gate.tryEnter();
            assertThat(entry).isInstanceOf(StreamOpenGate.Entry.Admitted.class);
            ((StreamOpenGate.Entry.Admitted) entry).close();
        }
    }

    @Test
    void countsWhyOpensWereRefused() {
        properties.setOpenBurst(1);
        gate = new StreamOpenGate(properties, meters, clock::get);
        gate.tryEnter();
        gate.tryEnter();

        assertThat(meters.counter("realtime.open.rejected", "reason", "rate").count()).isEqualTo(1);
        assertThat(meters.counter("realtime.open.attempts").count()).isEqualTo(2);
        assertThat(meters.counter("realtime.open.accepted").count()).isEqualTo(1);
    }
}

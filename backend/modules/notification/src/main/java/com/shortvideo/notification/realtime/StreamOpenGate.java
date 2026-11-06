package com.shortvideo.notification.realtime;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Admission control for opening a stream, run <em>before</em> authentication.
 *
 * <p>After a restart every client reconnects within seconds. Each open authenticates (a Redis
 * lookup and a PostgreSQL query) and, with virtual threads, nothing limits how many wait for one of
 * the 20 pooled connections: in the benchmark 4,000 reconnects queued over a thousand requests on
 * the pool and drove the CPU to six cores. Two limits keep that out of the database:
 *
 * <ul>
 *   <li>a token bucket on <b>opens per second</b>: past it a request is refused at once, touching
 *       neither Redis nor PostgreSQL;
 *   <li>a semaphore on <b>opens in flight</b>, well under the pool size, so a wave of reconnects can
 *       never hold every connection and starve REST, uploads and the media gateway.
 * </ul>
 *
 * A refused client is told when to retry, at a random moment that widens as refusals pile up, so the
 * crowd is spread out rather than sent back together.
 */
@Component
@ConditionalOnProperty(prefix = "shortvideo.realtime", name = "enabled", havingValue = "true")
public class StreamOpenGate {

    /** Why a request was refused. */
    public enum Refusal { RATE, CONCURRENCY }

    /** The outcome of asking to enter. */
    public sealed interface Entry {
        /** Admitted; {@link #close()} when the authentication phase is over. */
        final class Admitted implements Entry, AutoCloseable {
            private final Runnable release;
            private boolean released;

            Admitted(Runnable release) {
                this.release = release;
            }

            @Override
            public synchronized void close() {
                if (!released) {
                    released = true;
                    release.run();
                }
            }
        }

        record Refused(Refusal reason, Duration retryAfter) implements Entry {}
    }

    private final RealtimeProperties properties;
    private final Semaphore inFlight;
    private final AtomicInteger inFlightCount = new AtomicInteger();
    private final java.util.function.LongSupplier nanoTime;

    // Token bucket, guarded by `this`.
    private double tokens;
    private long lastRefill;

    // Refusals in the current second, to widen the retry window as the overload deepens.
    private final AtomicLong refusalWindow = new AtomicLong();
    private final AtomicInteger refusalsInWindow = new AtomicInteger();

    private final Counter attempts;
    private final Counter accepted;
    private final Counter refusedRate;
    private final Counter refusedConcurrency;

    @org.springframework.beans.factory.annotation.Autowired
    public StreamOpenGate(RealtimeProperties properties, MeterRegistry meters) {
        this(properties, meters, System::nanoTime);
    }

    StreamOpenGate(RealtimeProperties properties, MeterRegistry meters, java.util.function.LongSupplier nanoTime) {
        this.properties = properties;
        this.nanoTime = nanoTime;
        this.inFlight = new Semaphore(Math.max(1, properties.getMaxConcurrentOpens()));
        this.tokens = properties.getOpenBurst();
        this.lastRefill = nanoTime.getAsLong();
        this.attempts = Counter.builder("realtime.open.attempts").register(meters);
        this.accepted = Counter.builder("realtime.open.accepted").register(meters);
        this.refusedRate = Counter.builder("realtime.open.rejected").tag("reason", "rate").register(meters);
        this.refusedConcurrency = Counter.builder("realtime.open.rejected").tag("reason", "concurrency").register(meters);
        Gauge.builder("realtime.open.inflight", inFlightCount, AtomicInteger::get).register(meters);
    }

    public Entry tryEnter() {
        attempts.increment();
        if (!takeToken()) {
            refusedRate.increment();
            return new Entry.Refused(Refusal.RATE, retryAfter());
        }
        if (!inFlight.tryAcquire()) {
            refusedConcurrency.increment();
            return new Entry.Refused(Refusal.CONCURRENCY, retryAfter());
        }
        accepted.increment();
        inFlightCount.incrementAndGet();
        return new Entry.Admitted(() -> {
            inFlightCount.decrementAndGet();
            inFlight.release();
        });
    }

    private synchronized boolean takeToken() {
        int rate = properties.getMaxOpensPerSecond();
        if (rate <= 0) {
            return true; // off
        }
        long now = nanoTime.getAsLong();
        tokens = Math.min(properties.getOpenBurst(), tokens + (now - lastRefill) / 1e9 * rate);
        lastRefill = now;
        if (tokens < 1) {
            return false;
        }
        tokens -= 1;
        return true;
    }

    /**
     * Anywhere from 1 s up to a ceiling that grows with the refusals in the last second, so a mild
     * overload spreads the retries over a couple of seconds and a severe one over the full window.
     */
    private Duration retryAfter() {
        long second = nanoTime.getAsLong() / 1_000_000_000L;
        if (refusalWindow.getAndSet(second) != second) {
            refusalsInWindow.set(0);
        }
        int refusals = refusalsInWindow.incrementAndGet();
        int rate = Math.max(1, properties.getMaxOpensPerSecond());
        double ceiling = Math.min(properties.getRetryAfterMax().toSeconds(), 2 + 2.0 * refusals / rate);
        return Duration.ofSeconds(1 + (long) (ThreadLocalRandom.current().nextDouble() * Math.max(0, ceiling - 1)));
    }
}

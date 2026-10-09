package com.shortvideo.notification.realtime;

import com.shortvideo.shared.security.SessionRevalidator;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * The open streams of this instance, by account.
 *
 * <p>Every send goes through a virtual thread of its own. {@code SseEmitter#send} writes to the
 * servlet response and blocks until the socket accepts the bytes, so a client that stopped reading
 * would otherwise stall whoever called it: the Kafka listener (delaying every other account's
 * event) or the scheduler pool (four threads shared with the outbox relay and the cleanup jobs).
 * A stuck send costs one parked virtual thread and ends when the container's write timeout fails it.
 */
@Component
@ConditionalOnProperty(prefix = "shortvideo.realtime", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(RealtimeProperties.class)
public class SseRegistry implements ApplicationListener<ContextClosedEvent> {

    private static final Logger log = LoggerFactory.getLogger(SseRegistry.class);

    /** One open stream. */
    record Connection(String accountId, Instant tokenIssuedAt, SseEmitter emitter) {}

    /** What {@link #open} decided. */
    public sealed interface Opened {
        record Accepted(SseEmitter emitter) implements Opened {}
        record Rejected(Duration retryAfter) implements Opened {}
    }

    private final RealtimeProperties properties;
    private final SessionRevalidator revalidator;
    private final Map<String, List<Connection>> byAccount = new ConcurrentHashMap<>();
    private final AtomicInteger open = new AtomicInteger();
    private final ExecutorService sender = Executors.newVirtualThreadPerTaskExecutor();

    private final Counter sent;
    private final Counter sendFailed;
    private final Counter rejected;
    private final Counter opened;
    private final Counter revalidationClosed;
    private final Timer eventLatency;

    public SseRegistry(RealtimeProperties properties, SessionRevalidator revalidator, MeterRegistry meters) {
        this.properties = properties;
        this.revalidator = revalidator;
        Gauge.builder("realtime.connections", open, AtomicInteger::get).register(meters);
        this.sent = Counter.builder("realtime.events.sent").register(meters);
        this.sendFailed = Counter.builder("realtime.send.failed").register(meters);
        this.rejected = Counter.builder("realtime.rejected").register(meters);
        this.opened = Counter.builder("realtime.opened").register(meters);
        this.revalidationClosed = Counter.builder("realtime.revalidation.closed").register(meters);
        this.eventLatency = Timer.builder("realtime.event.latency")
                .description("From the notification event being written to it being handed to the stream")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meters);
    }

    /**
     * Opens a stream for the account, or refuses when the instance is full. Past the per-account
     * limit the oldest of that account's streams is closed to make room, which is what a user who
     * left a tab open and opened another wants.
     */
    public Opened open(String accountId, Instant tokenIssuedAt) {
        if (open.get() >= properties.getMaxConnections()) {
            rejected.increment();
            return new Opened.Rejected(properties.getRetryAfter());
        }
        SseEmitter emitter = new SseEmitter(timeoutMillis());
        Connection connection = new Connection(accountId, tokenIssuedAt, emitter);
        Runnable gone = () -> remove(connection);
        emitter.onCompletion(gone);
        emitter.onTimeout(() -> {
            gone.run();
            // The clock ran out, not the session: come straight back, but not all at the same moment.
            sender.execute(() -> byeAndFinish(connection, "timeout", true, randomBetween(
                    properties.getReconnectMin(), properties.getReconnectMax())));
        });
        emitter.onError(e -> gone.run());

        List<Connection> evicted = new ArrayList<>();
        byAccount.compute(accountId, (id, existing) -> {
            List<Connection> list = existing == null ? new CopyOnWriteArrayList<>() : existing;
            while (list.size() >= properties.getMaxConnectionsPerAccount()) {
                Connection oldest = list.remove(0);
                open.decrementAndGet();
                evicted.add(oldest);
            }
            list.add(connection);
            open.incrementAndGet();
            return list;
        });
        // Told not to come back: it was pushed out by a newer stream of the same account, and a client
        // that reconnected would push that one out in turn, forever.
        evicted.forEach(c -> sender.execute(() -> byeAndFinish(c, "evicted", false, 0)));
        opened.increment();
        // An immediate comment makes the response start now, so the browser fires `open` and
        // proxies see bytes, rather than both waiting for the first heartbeat. It also carries this
        // stream's own reconnect delay (a browser uses the last `retry:` it was given for its automatic
        // reconnects), random per stream, so even a server that dies without a word does not get its
        // whole crowd back on the same tick.
        long reconnectMs = randomBetween(properties.getReconnectMin(), properties.getReconnectMax());
        sender.execute(() -> send(connection, SseEmitter.event().comment("connected").reconnectTime(reconnectMs)));
        return new Opened.Accepted(emitter);
    }

    /** Tells every open stream of the account that something changed. */
    public void publish(String accountId, String type, String videoId, Instant occurredAt) {
        List<Connection> connections = byAccount.get(accountId);
        if (connections == null) {
            return;
        }
        String data = "{\"type\":" + quote(type) + ",\"videoId\":" + (videoId == null ? "null" : quote(videoId)) + "}";
        for (Connection connection : connections) {
            sender.execute(() -> {
                if (send(connection, SseEmitter.event().name("changed").data(data))) {
                    sent.increment();
                    if (occurredAt != null) {
                        eventLatency.record(Duration.between(occurredAt, Instant.now()).abs());
                    }
                }
            });
        }
    }

    @Scheduled(fixedDelayString = "${shortvideo.realtime.heartbeat-interval:20s}")
    public void heartbeat() {
        byAccount.values().forEach(list -> list.forEach(c -> sender.execute(() -> send(c, SseEmitter.event().comment("hb")))));
    }

    /** Closes the streams of accounts that have been suspended or have changed their password. */
    @Scheduled(fixedDelayString = "${shortvideo.realtime.revalidate-interval:60s}")
    public void revalidate() {
        byAccount.forEach((accountId, list) -> {
            for (Connection connection : list) {
                if (!revalidator.isStillEntitled(accountId, connection.tokenIssuedAt())) {
                    revalidationClosed.increment();
                    remove(connection);
                    // The account lost its standing: reconnecting would be refused, so say not to.
                    sender.execute(() -> byeAndFinish(connection, "session", false, 0));
                }
            }
        });
    }

    /** Before the web server starts its graceful shutdown, which would otherwise wait on every open stream. */
    @Override
    public void onApplicationEvent(ContextClosedEvent event) {
        closeAll();
    }

    void closeAll() {
        List<Connection> all = new ArrayList<>();
        byAccount.values().forEach(all::addAll);
        byAccount.clear();
        open.set(0);
        // Each client is told to come back at a different time within the shutdown window, so the
        // instance that replaces this one is not met by every client at once.
        all.forEach(c -> sender.execute(() -> byeAndFinish(
                c, "shutdown", true, randomBetween(properties.getReconnectMin(), properties.getShutdownReconnectMax()))));
        sender.shutdown();
        try {
            sender.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public int openConnections() {
        return open.get();
    }

    public int openForAccount(String accountId) {
        List<Connection> list = byAccount.get(accountId);
        return list == null ? 0 : list.size();
    }

    private boolean send(Connection connection, SseEmitter.SseEventBuilder event) {
        try {
            connection.emitter().send(event);
            return true;
        } catch (IOException | IllegalStateException e) {
            // The peer is gone, or the emitter had already completed. Either way the stream is over.
            sendFailed.increment();
            remove(connection);
            finish(connection);
            return false;
        }
    }

    /**
     * Ends a stream the server is closing on purpose, saying whether and when to reconnect, so the
     * client does not have to guess from a bare disconnect (which looks the same as a crash).
     */
    private void byeAndFinish(Connection connection, String reason, boolean reconnect, long afterMs) {
        try {
            connection.emitter().send(SseEmitter.event().name("bye").data(
                    "{\"reason\":\"" + reason + "\",\"reconnect\":" + reconnect + ",\"after\":" + afterMs + "}"));
        } catch (IOException | IllegalStateException e) {
            log.debug("Could not say goodbye to {}: {}", connection.accountId(), e.toString());
        }
        finish(connection);
    }

    private static long randomBetween(Duration min, Duration max) {
        long lo = min.toMillis();
        long hi = Math.max(lo, max.toMillis());
        return lo == hi ? lo : ThreadLocalRandom.current().nextLong(lo, hi + 1);
    }

    private void finish(Connection connection) {
        try {
            connection.emitter().complete();
        } catch (RuntimeException e) {
            log.debug("Emitter for {} was already finished", connection.accountId());
        }
    }

    private void remove(Connection connection) {
        byAccount.computeIfPresent(connection.accountId(), (id, list) -> {
            if (list.remove(connection)) {
                open.decrementAndGet();
            }
            return list.isEmpty() ? null : list;
        });
    }

    private long timeoutMillis() {
        long jitter = properties.getEmitterTimeoutJitter().toMillis();
        long extra = jitter <= 0 ? 0 : ThreadLocalRandom.current().nextLong(jitter);
        return properties.getEmitterTimeout().toMillis() + extra;
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}

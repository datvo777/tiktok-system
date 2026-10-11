package com.shortvideo.notification.realtime;

import com.shortvideo.shared.security.IpRateLimiter;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Caps how often one account may <em>open</em> a stream.
 *
 * <p>The per-account connection limit only bounds how many are live at once: a client that opens a
 * fourth stream closes its oldest, so reconnecting in a loop stays within it while every cycle
 * still builds an emitter, evicts a connection and writes a first frame. This is the brake on that
 * churn. A client that is merely flaky reconnects a few times a minute, far below it.
 *
 * <p>Shares {@link IpRateLimiter}'s fixed-window counter, and its fail-open behaviour on a Redis
 * outage, with the login, registration and upload limiters.
 */
@Component
@ConditionalOnProperty(prefix = "shortvideo.realtime", name = "enabled", havingValue = "true")
public class StreamOpenRateLimiter {

    private static final String PREFIX = "realtime:open:account:";

    private final IpRateLimiter counter;
    private final RealtimeProperties properties;
    private final Counter throttled;

    public StreamOpenRateLimiter(IpRateLimiter counter, RealtimeProperties properties, MeterRegistry meters) {
        this.counter = counter;
        this.properties = properties;
        this.throttled = Counter.builder("realtime.throttled").register(meters);
    }

    /** @return how long the client should wait, or empty when it may open a stream. */
    public Optional<Duration> check(String accountId) {
        int max = properties.getMaxOpensPerWindow();
        if (max <= 0) {
            return Optional.empty();
        }
        Duration window = properties.getOpenWindow();
        if (counter.increment(PREFIX + accountId, window) > max) {
            // Counted rather than logged: the client that trips this is, by definition, sending
            // them in a loop, and a log line each would be the same flood in another place.
            throttled.increment();
            return Optional.of(window);
        }
        return Optional.empty();
    }
}

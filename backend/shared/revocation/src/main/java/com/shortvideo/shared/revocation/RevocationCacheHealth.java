package com.shortvideo.shared.revocation;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

/** Tracks {@link RevocationCacheState} and exposes it as a gauge for local Grafana/Prometheus viewing. */
@Component
public class RevocationCacheHealth {

    private final AtomicReference<RevocationCacheState> state = new AtomicReference<>(RevocationCacheState.WARMING);
    private volatile Instant lastRebuildAt;

    public RevocationCacheHealth(MeterRegistry meters) {
        meters.gauge("revocation.cache.state", this, h -> h.state.get().ordinal());
    }

    public RevocationCacheState get() {
        return state.get();
    }

    public Instant lastRebuildAt() {
        return lastRebuildAt;
    }

    void markWarming() {
        state.set(RevocationCacheState.WARMING);
    }

    void markReady() {
        state.set(RevocationCacheState.READY);
        lastRebuildAt = Instant.now();
    }

    void markDegraded() {
        state.set(RevocationCacheState.DEGRADED);
    }

    /** Set when a rebuild pass has not completed successfully within the expected interval. */
    void markStaleIfOverdue(java.time.Duration maxAge) {
        Instant last = lastRebuildAt;
        if (last != null && last.isBefore(Instant.now().minus(maxAge)) && state.get() == RevocationCacheState.READY) {
            state.set(RevocationCacheState.STALE);
        }
    }
}

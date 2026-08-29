package com.shortvideo.shared.revocation;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Brief section 16: "After a Redis restart, rebuild hashes from active durable
 * revocations and run periodic drift checks." Runs once at startup (the cache is
 * empty on a fresh Redis) and then on a schedule, so a missed best-effort write
 * (e.g. a crash between a durable commit and its Redis update) self-heals within
 * one interval instead of silently caching a permissive value forever.
 *
 * <p>PostgreSQL is authoritative throughout: this only ever makes Redis match it,
 * never the other way around (Rule 12).
 */
@Component
class RevocationCacheRebuilder {

    private static final Logger log = LoggerFactory.getLogger(RevocationCacheRebuilder.class);

    private final JdbcRevocationStore store;
    private final RevocationCache cache;
    private final RevocationCacheHealth health;
    private final Counter rebuilds;
    private final Counter driftRemoved;

    RevocationCacheRebuilder(JdbcRevocationStore store, RevocationCache cache, RevocationCacheHealth health, MeterRegistry meters) {
        this.store = store;
        this.cache = cache;
        this.health = health;
        this.rebuilds = Counter.builder("revocation.cache.rebuilds").register(meters);
        this.driftRemoved = Counter.builder("revocation.cache.drift_entries_removed").register(meters);
    }

    @EventListener(ApplicationReadyEvent.class)
    void rebuildOnStartup() {
        rebuild();
    }

    @Scheduled(fixedDelayString = "${shortvideo.revocation.rebuild-interval:2m}")
    void periodicRebuild() {
        rebuild();
    }

    private void rebuild() {
        health.markWarming();
        try {
            List<ActiveRevocation> active = store.findAllActive();

            Map<SubjectKey, Set<String>> authoritative = new HashMap<>();
            for (ActiveRevocation r : active) {
                cache.putActive(r.subjectType(), r.subjectId(), r.sourceType(), r.reason());
                authoritative
                        .computeIfAbsent(new SubjectKey(r.subjectType(), r.subjectId()), k -> new HashSet<>())
                        .add(r.sourceType());
            }

            int removed = 0;
            for (Map.Entry<SubjectKey, Set<String>> entry : authoritative.entrySet()) {
                removed += cache.reconcileSubject(entry.getKey().subjectType(), entry.getKey().subjectId(), entry.getValue());
            }
            for (String cacheKey : cache.allCachedSubjectKeys()) {
                Map.Entry<String, String> parsed = cache.parseKey(cacheKey);
                SubjectKey key = new SubjectKey(parsed.getKey(), parsed.getValue());
                if (!authoritative.containsKey(key)) {
                    cache.deleteWholeKey(cacheKey);
                    removed++;
                }
            }

            if (removed > 0) {
                driftRemoved.increment(removed);
                log.info("Revocation cache rebuild removed {} stray entr{} absent from durable state",
                        removed, removed == 1 ? "y" : "ies");
            }
            rebuilds.increment();
            health.markReady();
        } catch (Exception e) {
            log.warn("Revocation cache rebuild failed; durable checks remain authoritative regardless", e);
            health.markDegraded();
        }
    }

    private record SubjectKey(String subjectType, String subjectId) {}
}

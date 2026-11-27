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
import org.springframework.beans.factory.annotation.Value;
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
    private final int pageSize;

    RevocationCacheRebuilder(
            JdbcRevocationStore store,
            RevocationCache cache,
            RevocationCacheHealth health,
            MeterRegistry meters,
            @Value("${shortvideo.revocation.rebuild-page-size:1000}") int pageSize) {
        this.store = store;
        this.pageSize = Math.max(1, pageSize);
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
            int removed = copyDurableStateIntoCache() + removeSubjectsNoLongerRevoked();

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

    /**
     * Durable to cache: every active revocation is written, and each subject's stray fields are
     * removed once all of its sources have been seen. Read a page at a time, in key order, so the
     * working set is one page plus the subject being assembled however many revocations exist;
     * a subject's sources are adjacent in that order, and the subject straddling a page boundary
     * is simply carried over to the next page.
     *
     * @return the stray fields removed
     */
    private int copyDurableStateIntoCache() {
        int removed = 0;
        ActiveRevocation last = null;
        SubjectKey current = null;
        Set<String> sources = new HashSet<>();
        while (true) {
            List<ActiveRevocation> page = store.findActivePage(last, pageSize);
            for (ActiveRevocation r : page) {
                SubjectKey key = new SubjectKey(r.subjectType(), r.subjectId());
                if (!key.equals(current)) {
                    removed += reconcile(current, sources);
                    current = key;
                    sources = new HashSet<>();
                }
                cache.putActive(r.subjectType(), r.subjectId(), r.sourceType(), r.reason());
                sources.add(r.sourceType());
            }
            if (page.size() < pageSize) {
                break;
            }
            last = page.get(page.size() - 1);
        }
        return removed + reconcile(current, sources);
    }

    private int reconcile(SubjectKey subject, Set<String> sources) {
        return subject == null ? 0 : cache.reconcileSubject(subject.subjectType(), subject.subjectId(), sources);
    }

    /**
     * Cache to durable: a cached subject with no active revocation left at all is dropped. Asked of
     * the database a batch of keys at a time, at the moment each batch is read, rather than checked
     * against a snapshot taken before the walk: a revocation committed meanwhile is seen as active
     * instead of having its fresh cache entry deleted.
     *
     * @return the whole keys removed
     */
    private int removeSubjectsNoLongerRevoked() {
        int[] removed = {0};
        cache.forEachCachedSubjectKeyBatch(pageSize, keys -> {
            Map<String, Map<String, String>> idsByType = new HashMap<>();
            for (String cacheKey : keys) {
                Map.Entry<String, String> parsed = cache.parseKey(cacheKey);
                idsByType.computeIfAbsent(parsed.getKey(), t -> new HashMap<>()).put(parsed.getValue(), cacheKey);
            }
            idsByType.forEach((type, cacheKeyById) -> {
                Set<String> active = store.activeAmong(type, cacheKeyById.keySet());
                cacheKeyById.forEach((id, cacheKey) -> {
                    if (!active.contains(id)) {
                        cache.deleteWholeKey(cacheKey);
                        removed[0]++;
                    }
                });
            });
        });
        return removed[0];
    }

    private record SubjectKey(String subjectType, String subjectId) {}
}

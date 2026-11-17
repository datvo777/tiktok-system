package com.shortvideo.shared.security;

import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Makes Redis hold every unexpired session revocation PostgreSQL does, then says so by writing the
 * completeness marker {@link SessionTokenDenyList} relies on. Runs at startup and on a schedule, so a
 * restarted or flushed Redis is refilled within one interval and a missed best-effort write self-heals.
 * Until the marker is back, lookups that miss in Redis ask PostgreSQL, so the gap costs queries, not
 * correctness. PostgreSQL is authoritative throughout: this only ever makes Redis match it.
 */
@Component
public class SessionDenyListRebuilder {

    private static final Logger log = LoggerFactory.getLogger(SessionDenyListRebuilder.class);

    private final RevokedSessionStore store;
    private final SessionTokenDenyList denyList;
    private final int pageSize;

    SessionDenyListRebuilder(
            RevokedSessionStore store,
            SessionTokenDenyList denyList,
            @Value("${shortvideo.session-deny-list.rebuild-page-size:1000}") int pageSize) {
        this.store = store;
        this.denyList = denyList;
        this.pageSize = Math.max(1, pageSize);
    }

    @EventListener(ApplicationReadyEvent.class)
    void rebuildOnStartup() {
        rebuild();
    }

    @Scheduled(fixedDelayString = "${shortvideo.session-deny-list.rebuild-interval:1m}")
    void periodicRebuild() {
        rebuild();
    }

    public void rebuild() {
        try {
            int expired = store.deleteExpired();
            int copied = 0;
            String after = null;
            while (true) {
                List<RevokedSessionStore.Row> page = store.findActivePage(after, pageSize);
                for (RevokedSessionStore.Row row : page) {
                    denyList.restore(row.tokenId(), row.expiresAt());
                }
                copied += page.size();
                if (page.size() < pageSize) {
                    break;
                }
                after = page.get(page.size() - 1).tokenId();
            }
            // Last, so a reader that sees the marker is guaranteed the entries above.
            denyList.markComplete();
            log.debug("Session deny-list rebuilt at {}: {} entries copied, {} expired rows removed", Instant.now(), copied, expired);
        } catch (RuntimeException e) {
            log.warn("Session deny-list rebuild failed; lookups that miss in Redis will ask PostgreSQL", e);
        }
    }
}

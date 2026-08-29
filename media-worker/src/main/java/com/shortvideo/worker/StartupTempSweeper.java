package com.shortvideo.worker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Sweeps orphaned {@code processing-temp/} objects at startup (brief section
 * 14.1). A partially written temp prefix is always discardable — {@code
 * processed/} is only written after validation, so a worker that was killed
 * mid-job leaves no half-published asset, only orphaned staging objects.
 */
@Component
class StartupTempSweeper {

    private static final Logger log = LoggerFactory.getLogger(StartupTempSweeper.class);
    private static final String PREFIX = "processing-temp/";

    private final MinioObjectStore store;

    StartupTempSweeper(MinioObjectStore store) {
        this.store = store;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void sweep() {
        try {
            var keys = store.listKeysUnder(PREFIX);
            keys.forEach(store::delete);
            if (!keys.isEmpty()) {
                log.info("Swept {} orphaned processing-temp object(s) at startup", keys.size());
            }
        } catch (Exception e) {
            log.warn("Startup processing-temp sweep failed; continuing", e);
        }
    }
}

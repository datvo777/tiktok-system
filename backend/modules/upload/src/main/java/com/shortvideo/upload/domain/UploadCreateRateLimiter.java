package com.shortvideo.upload.domain;

import com.shortvideo.shared.security.IpRateLimiter;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Caps how many uploads one account may <em>start</em> per window, and how many may be started
 * across all accounts.
 *
 * <p>The open-session quota only bounds how many are live at once, so opening five, letting them
 * expire and repeating would still create hundreds of draft rows and sessions a day; the
 * per-account window stops that. It cannot stop many accounts at once, though, and what the object
 * store runs out of is bandwidth and disk, not request count. The global cap is the brake at the
 * one point where the backend decides to hand out write access at all.
 *
 * <p>Shares {@link IpRateLimiter}'s fixed-window counter (and its fail-open behaviour on a Redis
 * outage) with the login and registration limiters.
 */
@Component
class UploadCreateRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(UploadCreateRateLimiter.class);
    private static final String PREFIX = "upload:create:account:";
    private static final String GLOBAL_KEY = "upload:create:global";

    private final IpRateLimiter counter;
    private final boolean enabled;
    private final int maxPerWindow;
    private final Duration window;
    private final int globalMaxPerWindow;
    private final Duration globalWindow;

    UploadCreateRateLimiter(
            IpRateLimiter counter,
            @Value("${shortvideo.upload.create-rate-limit.enabled:true}") boolean enabled,
            @Value("${shortvideo.upload.create-rate-limit.max-per-window:30}") int maxPerWindow,
            @Value("${shortvideo.upload.create-rate-limit.window:1h}") Duration window,
            @Value("${shortvideo.upload.create-rate-limit.global-max-per-window:600}") int globalMaxPerWindow,
            @Value("${shortvideo.upload.create-rate-limit.global-window:1m}") Duration globalWindow) {
        this.counter = counter;
        this.enabled = enabled;
        this.maxPerWindow = maxPerWindow;
        this.window = window;
        this.globalMaxPerWindow = globalMaxPerWindow;
        this.globalWindow = globalWindow;
    }

    void checkAllowed(String accountId) {
        if (!enabled) {
            return;
        }
        if (counter.increment(PREFIX + accountId, window) > maxPerWindow) {
            log.warn("Throttled upload creation for account {}", accountId);
            throw new UploadExceptions.UploadRateLimited(window);
        }
        // After the per-account check, so one account hammering its own limit cannot spend the
        // shared budget. Zero turns the global cap off.
        if (globalMaxPerWindow > 0 && counter.increment(GLOBAL_KEY, globalWindow) > globalMaxPerWindow) {
            log.warn("Upload creation over the global cap of {} per {}", globalMaxPerWindow, globalWindow);
            throw new UploadExceptions.UploadCapacityExceeded(globalWindow);
        }
    }
}

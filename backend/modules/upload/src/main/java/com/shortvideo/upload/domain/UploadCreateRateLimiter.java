package com.shortvideo.upload.domain;

import com.shortvideo.shared.security.IpRateLimiter;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Caps how many uploads one account may <em>start</em> per window. The open-session
 * quota only bounds how many are live at once, so opening five, letting them expire
 * and repeating would still create hundreds of draft rows and sessions a day. Shares
 * {@link IpRateLimiter}'s fixed-window counter (and its fail-open behaviour on a Redis
 * outage) with the login and registration limiters.
 */
@Component
class UploadCreateRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(UploadCreateRateLimiter.class);
    private static final String PREFIX = "upload:create:account:";

    private final IpRateLimiter counter;
    private final boolean enabled;
    private final int maxPerWindow;
    private final Duration window;

    UploadCreateRateLimiter(
            IpRateLimiter counter,
            @Value("${shortvideo.upload.create-rate-limit.enabled:true}") boolean enabled,
            @Value("${shortvideo.upload.create-rate-limit.max-per-window:30}") int maxPerWindow,
            @Value("${shortvideo.upload.create-rate-limit.window:1h}") Duration window) {
        this.counter = counter;
        this.enabled = enabled;
        this.maxPerWindow = maxPerWindow;
        this.window = window;
    }

    void checkAllowed(String accountId) {
        if (!enabled) {
            return;
        }
        if (counter.increment(PREFIX + accountId, window) > maxPerWindow) {
            log.warn("Throttled upload creation for account {}", accountId);
            throw new UploadExceptions.UploadRateLimited(window);
        }
    }
}

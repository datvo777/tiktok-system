package com.shortvideo.publication.domain;

import com.shortvideo.shared.security.IpRateLimiter;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Caps how often one account may publish or unpublish, counting both together.
 *
 * <p>Each call that really changes state appends an event, and a published video sends its owner a
 * notification and rewrites the search index, so flipping a video back and forth is cheap for the
 * caller and costly everywhere downstream. A repeated call that changes nothing emits nothing, but
 * it is counted all the same: the limit is on requests, not on events.
 *
 * <p>Shares {@link IpRateLimiter}'s fixed-window counter (and its fail-open behaviour on a Redis
 * outage) with the login, registration and upload limiters.
 */
@Component
class PublishToggleRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(PublishToggleRateLimiter.class);
    private static final String PREFIX = "publication:toggle:account:";

    private final IpRateLimiter counter;
    private final boolean enabled;
    private final int maxPerWindow;
    private final Duration window;

    PublishToggleRateLimiter(
            IpRateLimiter counter,
            @Value("${shortvideo.publication.toggle-rate-limit.enabled:true}") boolean enabled,
            @Value("${shortvideo.publication.toggle-rate-limit.max-per-window:20}") int maxPerWindow,
            @Value("${shortvideo.publication.toggle-rate-limit.window:10m}") Duration window) {
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
            log.warn("Throttled publish/unpublish for account {}", accountId);
            throw new PublicationExceptions.PublishRateLimited(window);
        }
    }
}

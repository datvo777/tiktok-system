package com.shortvideo.social.domain;

import com.shortvideo.shared.security.IpRateLimiter;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Caps how often one account may comment or reply, counting both together.
 *
 * <p>A comment is not a cheap write. It raises the video's comment total, which the feed ranks on
 * at twice the weight of a like, and unlike a like nothing makes a second one from the same account
 * a no-op. Without a limit a single account could post comments in a loop and lift its own video, or
 * sink a rival's out of the feed's candidate pool by crowding it, for the price of one request each.
 * Each one also writes an outbox row and a notification, so the cost lands on the relay too.
 *
 * <p>The default is generous for a person (three a minute, sustained) and far below a script. It
 * slows manipulation rather than preventing it: the ranking still counts every comment that gets
 * through, so this does not bound what one account can add to a video's score over a day.
 *
 * <p>Shares {@link IpRateLimiter}'s fixed-window counter, and its fail-open behaviour on a Redis
 * outage, with the login, registration, upload and publish limiters.
 */
@Component
class CommentRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(CommentRateLimiter.class);
    private static final String PREFIX = "social:comment:account:";

    private final IpRateLimiter counter;
    private final boolean enabled;
    private final int maxPerWindow;
    private final Duration window;

    CommentRateLimiter(
            IpRateLimiter counter,
            @Value("${shortvideo.social.comment-rate-limit.enabled:true}") boolean enabled,
            @Value("${shortvideo.social.comment-rate-limit.max-per-window:30}") int maxPerWindow,
            @Value("${shortvideo.social.comment-rate-limit.window:10m}") Duration window) {
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
            log.warn("Throttled comment for account {}", accountId);
            throw new SocialExceptions.CommentRateLimited(window);
        }
    }
}

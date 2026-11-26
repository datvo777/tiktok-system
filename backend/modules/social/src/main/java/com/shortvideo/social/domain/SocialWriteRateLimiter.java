package com.shortvideo.social.domain;

import com.shortvideo.shared.security.IpRateLimiter;
import java.time.Duration;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Caps how often one account may like, follow, share or record a view, each counted on its own.
 * Comments and replies have their own limit, in {@link CommentRateLimiter}.
 *
 * <p>Like and follow are idempotent only while the row exists. Unliking and liking again inserts a
 * new row, which writes outbox events and sends the creator another notification, and so does
 * unfollowing and following again, so a loop of either floods an inbox and the relay. Every share
 * inserts a row, and every view is a write.
 *
 * <p>Unlike and unfollow are deliberately not limited, so an account can always take back what it
 * did. A loop still has to pass through like or follow each time, and without one the withdrawal
 * finds no row and changes nothing.
 *
 * <p>The defaults are far above what a person does. A view is recorded after about three seconds of
 * watching, so the view limit of one a second sustained is only reached by a script, or by someone
 * skimming very short clips without a pause for ten minutes. A refused view is dropped: the client
 * records views on a best-effort basis and ignores the answer.
 *
 * <p>Shares {@link IpRateLimiter}'s fixed-window counter, and its fail-open behaviour on a Redis
 * outage, with the other limiters.
 */
@Component
class SocialWriteRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(SocialWriteRateLimiter.class);
    private static final String PREFIX = "social:";

    enum Action {
        LIKE,
        FOLLOW,
        SHARE,
        VIEW
    }

    private final IpRateLimiter counter;
    private final boolean enabled;
    private final int maxLikes;
    private final int maxFollows;
    private final int maxShares;
    private final int maxViews;
    private final Duration window;

    SocialWriteRateLimiter(
            IpRateLimiter counter,
            @Value("${shortvideo.social.write-rate-limit.enabled:true}") boolean enabled,
            @Value("${shortvideo.social.write-rate-limit.max-likes-per-window:300}") int maxLikes,
            @Value("${shortvideo.social.write-rate-limit.max-follows-per-window:100}") int maxFollows,
            @Value("${shortvideo.social.write-rate-limit.max-shares-per-window:60}") int maxShares,
            @Value("${shortvideo.social.write-rate-limit.max-views-per-window:600}") int maxViews,
            @Value("${shortvideo.social.write-rate-limit.window:10m}") Duration window) {
        this.counter = counter;
        this.enabled = enabled;
        this.maxLikes = maxLikes;
        this.maxFollows = maxFollows;
        this.maxShares = maxShares;
        this.maxViews = maxViews;
        this.window = window;
    }

    void checkAllowed(Action action, String accountId) {
        if (!enabled) {
            return;
        }
        String name = action.name().toLowerCase(Locale.ROOT);
        long count = counter.increment(PREFIX + name + ":account:" + accountId, window);
        int max = maxFor(action);
        if (count > max) {
            // Once per window per account: a script past the limit would otherwise write a line per request.
            if (count == max + 1L) {
                log.warn("Throttling {} for account {}", name, accountId);
            }
            throw new SocialExceptions.WriteRateLimited(window);
        }
    }

    private int maxFor(Action action) {
        return switch (action) {
            case LIKE -> maxLikes;
            case FOLLOW -> maxFollows;
            case SHARE -> maxShares;
            case VIEW -> maxViews;
        };
    }
}

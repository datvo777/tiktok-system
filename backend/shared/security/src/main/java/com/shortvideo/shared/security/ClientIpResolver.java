package com.shortvideo.shared.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Resolves the caller's IP for the per-IP rate limiters ({@link LoginRateLimiter},
 * {@link RegisterRateLimiter}).
 *
 * <p>{@code X-Forwarded-For} is only consulted when a trusted proxy is configured,
 * because the header is client-supplied: honouring it unconditionally would let
 * anyone reset their own rate-limit bucket by varying one header, which is worse
 * than having no limiter at all. With no proxy configured the socket address is
 * the only thing a caller cannot forge.
 *
 * <p>Each hop in a trusted chain <em>appends</em> its view of the immediate peer
 * to the right of whatever the header already held — a client can prepend as
 * many fake entries as it likes before the request ever reaches our infrastructure,
 * but it cannot control what a trusted proxy appends after them. So the address
 * our own edge actually observed is {@link #trustedHopCount} entries from the
 * <em>right</em>, never the leftmost: taking the leftmost entry (as an earlier
 * version of this class did) reads back exactly the attacker-controlled text,
 * making the header worse than not trusting it at all.
 */
@Component
public class ClientIpResolver {

    private final boolean trustForwardedFor;
    private final int trustedHopCount;

    public ClientIpResolver(
            @Value("${shortvideo.rate-limit.trust-forwarded-for:false}") boolean trustForwardedFor,
            @Value("${shortvideo.rate-limit.trusted-hop-count:1}") int trustedHopCount) {
        this.trustForwardedFor = trustForwardedFor;
        this.trustedHopCount = trustedHopCount;
    }

    public String resolve(HttpServletRequest request) {
        if (trustForwardedFor) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                String[] hops = forwarded.split(",");
                int index = hops.length - trustedHopCount;
                // Fewer hops present than configured trusted proxies means the
                // chain does not match expectations — fall through to the socket
                // address rather than guess at an index that might not be ours.
                if (index >= 0) {
                    return hops[index].trim();
                }
            }
        }
        return request.getRemoteAddr();
    }
}

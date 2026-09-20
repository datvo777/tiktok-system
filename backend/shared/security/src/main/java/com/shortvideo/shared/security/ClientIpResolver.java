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
 */
@Component
public class ClientIpResolver {

    private final boolean trustForwardedFor;

    public ClientIpResolver(@Value("${shortvideo.rate-limit.trust-forwarded-for:false}") boolean trustForwardedFor) {
        this.trustForwardedFor = trustForwardedFor;
    }

    public String resolve(HttpServletRequest request) {
        if (trustForwardedFor) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                // Left-most entry is the original client; the rest are proxies.
                return forwarded.split(",")[0].trim();
            }
        }
        return request.getRemoteAddr();
    }
}

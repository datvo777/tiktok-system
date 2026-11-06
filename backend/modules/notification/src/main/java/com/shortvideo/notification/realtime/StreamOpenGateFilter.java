package com.shortvideo.notification.realtime;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Puts {@link StreamOpenGate} in front of the stream endpoint, ahead of authentication. Created by
 * the security configuration rather than registered as a bean, so the servlet container does not also
 * install it a second time outside the security chain.
 */
public final class StreamOpenGateFilter extends OncePerRequestFilter {

    public static final String PATH = "/api/v1/events/stream";

    private final StreamOpenGate gate;

    public StreamOpenGateFilter(StreamOpenGate gate) {
        this.gate = gate;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"GET".equals(request.getMethod()) || !PATH.equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        switch (gate.tryEnter()) {
            case StreamOpenGate.Entry.Refused refused -> {
                response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
                response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(refused.retryAfter().toSeconds()));
            }
            case StreamOpenGate.Entry.Admitted admitted -> {
                // For an SseEmitter the chain returns as soon as the handler has handed the emitter back
                // and the request has gone asynchronous, so this covers authentication and registration
                // and not the life of the stream.
                try (admitted) {
                    chain.doFilter(request, response);
                }
            }
        }
    }
}

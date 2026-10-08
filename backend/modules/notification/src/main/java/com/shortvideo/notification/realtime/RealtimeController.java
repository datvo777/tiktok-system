package com.shortvideo.notification.realtime;

import com.shortvideo.shared.security.AuthenticatedAccount;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * The server-sent stream of "something changed" hints. Authorised by the same session cookie as
 * every other route (EventSource cannot set headers, and the app is single-origin), and scoped to
 * the caller: the account comes from the verified principal, never from the request.
 *
 * <p>Absent when {@code shortvideo.realtime.enabled} is false.
 */
@RestController
@RequestMapping("/api/v1/events")
@Tag(name = "Realtime")
@ConditionalOnProperty(prefix = "shortvideo.realtime", name = "enabled", havingValue = "true")
public class RealtimeController {

    private final SseRegistry registry;

    public RealtimeController(SseRegistry registry) {
        this.registry = registry;
    }

    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Stream of change hints for the caller; the REST API stays the source of truth")
    public ResponseEntity<SseEmitter> stream(@AuthenticationPrincipal AuthenticatedAccount caller) {
        return switch (registry.open(caller.accountId(), caller.issuedAt())) {
            case SseRegistry.Opened.Accepted accepted -> ResponseEntity.ok()
                    .cacheControl(CacheControl.noCache())
                    // nginx buffers responses by default, which would hold events until the buffer filled.
                    .header("X-Accel-Buffering", "no")
                    .body(accepted.emitter());
            case SseRegistry.Opened.Rejected rejected -> ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .header(HttpHeaders.RETRY_AFTER, String.valueOf(rejected.retryAfter().toSeconds()))
                    .build();
        };
    }
}

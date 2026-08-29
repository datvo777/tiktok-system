package com.shortvideo.playback;

import com.shortvideo.shared.security.AuthenticatedAccount;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * The media gateway (brief section 8). The only read path for processed media
 * (Rule 18): every request is authorized here, then streamed with a bounded
 * buffer directly from MinIO (Rule 19) — never buffered whole in the JVM.
 */
@RestController
@RequestMapping("/media/videos")
@Tag(name = "Media gateway")
public class MediaGatewayController {

    private static final Logger log = LoggerFactory.getLogger(MediaGatewayController.class);

    private final MediaPathValidator pathValidator;
    private final MediaAuthorizer authorizer;
    private final MediaObjectStreamer streamer;

    public MediaGatewayController(
            MediaPathValidator pathValidator, MediaAuthorizer authorizer, MediaObjectStreamer streamer) {
        this.pathValidator = pathValidator;
        this.authorizer = authorizer;
        this.streamer = streamer;
    }

    @GetMapping("/{videoId}/{processingVersion}/{*assetPath}")
    @Operation(summary = "Fetch a processed HLS asset; authorized on every request (brief section 8)")
    public ResponseEntity<StreamingResponseBody> asset(
            @PathVariable String videoId,
            @PathVariable String processingVersion,
            @PathVariable String assetPath,
            @AuthenticationPrincipal AuthenticatedAccount viewer,
            HttpServletRequest request) {

        MediaObjectKey key = pathValidator.validate(videoId, processingVersion, assetPath);
        authorizer.authorize(request, key, viewer);

        MediaObjectStreamer.PreparedStream prepared =
                streamer.prepare(key, request.getHeader(HttpHeaders.RANGE));

        HttpStatus status = prepared.partial() ? HttpStatus.PARTIAL_CONTENT : HttpStatus.OK;
        ResponseEntity.BodyBuilder response = ResponseEntity.status(status)
                .contentType(contentTypeFor(key.assetPath()))
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .contentLength(prepared.contentLength())
                // No shared or browser cache may hold media that must be
                // reauthorized on every request (brief section 8).
                .cacheControl(CacheControl.noStore().cachePrivate());
        if (prepared.partial()) {
            response.header(HttpHeaders.CONTENT_RANGE, prepared.contentRange());
        }

        log.debug("Streaming {} bytes ({}) for viewer {} on {}",
                prepared.contentLength(), status, viewer.accountId(), key.objectKey());
        return response.body(prepared.body());
    }

    private MediaType contentTypeFor(String assetPath) {
        String lower = assetPath.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".m3u8")) {
            return MediaType.valueOf("application/vnd.apple.mpegurl");
        }
        if (lower.endsWith(".ts")) {
            return MediaType.valueOf("video/mp2t");
        }
        if (lower.endsWith(".m4s") || lower.endsWith(".mp4")) {
            return MediaType.valueOf("video/mp4");
        }
        if (lower.endsWith(".vtt")) {
            return MediaType.valueOf("text/vtt");
        }
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return MediaType.IMAGE_JPEG;
        }
        if (lower.endsWith(".png")) {
            return MediaType.IMAGE_PNG;
        }
        return MediaType.APPLICATION_OCTET_STREAM;
    }
}

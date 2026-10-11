package com.shortvideo.playback;

import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.MinioClient;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.errors.ErrorResponseException;
import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * Range-aware, bounded-buffer streaming from MinIO (brief section 8, Rule 19).
 *
 * <p>Never fetches a whole object to slice it: offset and length go straight to
 * MinIO {@code GetObject}. The copy loop uses a fixed-size buffer, never a
 * {@code byte[]} or {@code ByteArrayResource} holding the whole object.
 */
@Component
@EnableConfigurationProperties(MediaStreamProperties.class)
class MediaObjectStreamer {

    private final MinioClient minioClient;
    private final String bucket;
    private final MediaStreamProperties properties;
    private final Semaphore concurrencyLimiter;

    MediaObjectStreamer(
            MinioClient minioClient,
            @Value("${shortvideo.minio.bucket}") String bucket,
            MediaStreamProperties properties) {
        this.minioClient = minioClient;
        this.bucket = bucket;
        this.properties = properties;
        this.concurrencyLimiter = new Semaphore(properties.getMaxConcurrentStreams());
    }

    PreparedStream prepare(MediaObjectKey key, String rangeHeader) {
        StatObjectResponse stat;
        try {
            stat = minioClient.statObject(
                    StatObjectArgs.builder().bucket(bucket).object(key.objectKey()).build());
        } catch (ErrorResponseException notFound) {
            throw new MediaAuthorizationException.ObjectMissing("No such object");
        } catch (Exception e) {
            throw new IllegalStateException("Failed to stat media object", e);
        }

        long totalSize = stat.size();
        Range range = parseRange(rangeHeader, totalSize);
        if (range instanceof Range.Unsatisfiable) {
            // RFC 9110 §15.5.17: a syntactically valid range that cannot be met gets
            // 416 plus the object's true size, not a silent 200 with the whole body.
            throw new MediaRangeNotSatisfiableException(totalSize);
        }

        boolean partial = range instanceof Range.Satisfiable;
        long offset = partial ? ((Range.Satisfiable) range).start() : 0;
        long length = partial ? ((Range.Satisfiable) range).length() : totalSize;

        // Acquired here, before the controller builds the response, so exhaustion
        // becomes a 503 the client can act on. Acquiring inside the body ran after
        // the status line and Content-Length were already committed, so an
        // overloaded gateway answered "200, here are N bytes" and then delivered
        // fewer — which hls.js reads as a corrupt segment and retries, adding load
        // to a gateway that was already saturated.
        if (!concurrencyLimiter.tryAcquire()) {
            throw new MediaStreamsExhaustedException("Too many concurrent media streams");
        }

        StreamingResponseBody body = out -> {
            try {
                streamRange(key.objectKey(), offset, length, out);
            } finally {
                concurrencyLimiter.release();
            }
        };
        String contentRange = partial ? "bytes " + offset + "-" + (offset + length - 1) + "/" + totalSize : null;
        return new PreparedStream(body, length, partial, contentRange);
    }

    private void streamRange(String objectKey, long offset, long length, OutputStream out) throws IOException {
        try (GetObjectResponse object = minioClient.getObject(GetObjectArgs.builder()
                .bucket(bucket)
                .object(objectKey)
                .offset(offset)
                .length(length)
                .build())) {
            byte[] buffer = new byte[properties.getStreamBufferBytes()];
            int read;
            while ((read = object.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            out.flush();
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Failed to stream media object " + objectKey, e);
        }
    }

    /**
     * Supports "bytes=start-end", "bytes=start-", and the "bytes=-suffixLength"
     * forms. Single range only.
     *
     * <p>Three outcomes, kept distinct: {@link Range.Ignored} for a header this
     * gateway does not parse (respond 200 with the whole object), {@link
     * Range.Unsatisfiable} for a well-formed range outside the object (respond 416),
     * and {@link Range.Satisfiable}. Collapsing the first two onto {@code null}
     * meant an unsatisfiable range silently returned the entire object.
     */
    Range parseRange(String header, long totalSize) {
        if (header == null || !header.startsWith("bytes=")) {
            return Range.IGNORED;
        }
        String spec = header.substring("bytes=".length()).split(",", 2)[0].trim();
        String[] parts = spec.split("-", 2);
        if (parts.length != 2) {
            return Range.IGNORED;
        }
        try {
            if (parts[0].isEmpty()) {
                long suffixLength = Long.parseLong(parts[1]);
                // "bytes=-0" asks for the last zero bytes, and "bytes=--5" parses as
                // a suffix of -5. Both used to produce start > end, hence a negative
                // Content-Length and a MinIO argument error thrown mid-response.
                if (suffixLength <= 0) {
                    return Range.UNSATISFIABLE;
                }
                if (totalSize == 0) {
                    return Range.UNSATISFIABLE;
                }
                long start = Math.max(0, totalSize - suffixLength);
                return new Range.Satisfiable(start, totalSize - 1);
            }
            long start = Long.parseLong(parts[0]);
            long end = parts[1].isEmpty() ? totalSize - 1 : Long.parseLong(parts[1]);
            if (start < 0 || end < start) {
                return Range.IGNORED; // malformed rather than merely unmeetable
            }
            if (start >= totalSize) {
                return Range.UNSATISFIABLE;
            }
            return new Range.Satisfiable(start, Math.min(end, totalSize - 1));
        } catch (NumberFormatException e) {
            return Range.IGNORED;
        }
    }

    /** The three ways a Range header can resolve. */
    sealed interface Range {
        Range IGNORED = new Ignored();
        Range UNSATISFIABLE = new Unsatisfiable();

        record Ignored() implements Range {}

        record Unsatisfiable() implements Range {}

        record Satisfiable(long start, long end) implements Range {
            long length() {
                return end - start + 1;
            }
        }
    }

    record PreparedStream(StreamingResponseBody body, long contentLength, boolean partial, String contentRange) {}
}

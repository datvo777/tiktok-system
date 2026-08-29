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
        long[] range = rangeHeader == null ? null : parseRange(rangeHeader, totalSize);
        long offset = range == null ? 0 : range[0];
        long length = range == null ? totalSize : range[1] - range[0] + 1;
        boolean partial = range != null;

        StreamingResponseBody body = out -> streamRange(key.objectKey(), offset, length, out);
        String contentRange = partial ? "bytes " + offset + "-" + (offset + length - 1) + "/" + totalSize : null;
        return new PreparedStream(body, length, partial, contentRange);
    }

    private void streamRange(String objectKey, long offset, long length, OutputStream out) throws IOException {
        if (!concurrencyLimiter.tryAcquire()) {
            throw new IOException("Too many concurrent media streams");
        }
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
        } finally {
            concurrencyLimiter.release();
        }
    }

    /** Supports "bytes=start-end", "bytes=start-", and the "bytes=-suffixLength" forms. Single range only. */
    long[] parseRange(String header, long totalSize) {
        if (!header.startsWith("bytes=")) {
            return null;
        }
        String spec = header.substring("bytes=".length()).split(",", 2)[0].trim();
        String[] parts = spec.split("-", 2);
        if (parts.length != 2) {
            return null;
        }
        try {
            if (parts[0].isEmpty()) {
                long suffixLength = Long.parseLong(parts[1]);
                long start = Math.max(0, totalSize - suffixLength);
                return new long[] {start, totalSize - 1};
            }
            long start = Long.parseLong(parts[0]);
            long end = parts[1].isEmpty() ? totalSize - 1 : Long.parseLong(parts[1]);
            if (start < 0 || start >= totalSize || end < start) {
                return null;
            }
            return new long[] {start, Math.min(end, totalSize - 1)};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    record PreparedStream(StreamingResponseBody body, long contentLength, boolean partial, String contentRange) {}
}

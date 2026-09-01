package com.shortvideo.worker;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * FFmpeg/FFprobe orchestration (brief section 14). One 720p rendition for the
 * first version; the master playlist is hand-written so even a single rendition
 * has a proper master/variant structure.
 *
 * <p>The input bytes are attacker-controlled: a client uploads whatever it likes
 * straight to MinIO, and FFmpeg picks its demuxer from the file's content, not
 * its name or the client-declared MIME type. A file whose contents are an HLS or
 * {@code concat} playlist can therefore ask FFmpeg to open unrelated URLs —
 * {@code file:///…} for local disclosure, {@code http://…} for SSRF from inside
 * the private network — and have the result muxed into a rendition the uploader
 * can then watch. Current FFmpeg releases refuse both by default, but that
 * default is the only thing standing in the way and nothing here pins an FFmpeg
 * version, so the restriction is stated explicitly rather than inherited:
 *
 * <ol>
 *   <li>{@code -protocol_whitelist file} on both the probe and the transcode, so
 *       no demuxer can reach a network or pipe protocol whatever the container
 *       claims;
 *   <li>the probed container must be in {@link #ALLOWED_FORMATS}, so playlist
 *       formats are rejected outright rather than merely constrained;
 *   <li>{@code -f} pins the transcode to the demuxer the probe already chose, so
 *       the two passes cannot disagree about what the file is.
 * </ol>
 */
@Component
class HlsTranscoder {

    /**
     * Keyed by FFprobe's {@code format_name}, which is the full comma-joined
     * demuxer name (e.g. {@code mov,mp4,m4a,3gp,3g2,mj2}) and is also accepted
     * verbatim as an {@code -f} value. Mirrors the browser-side allowlist in
     * {@code web/src/Upload.tsx}; notably absent are {@code hls}, {@code concat},
     * {@code image2} and every other format whose job is to reference other URLs.
     */
    private static final Set<String> ALLOWED_FORMATS = Set.of(
            "mov,mp4,m4a,3gp,3g2,mj2", // .mp4, .mov, .m4v
            "matroska,webm",           // .mkv, .webm
            "avi");                    // .avi

    private static final List<String> PROTOCOL_WHITELIST = List.of("-protocol_whitelist", "file");

    private final ProcessRunner processRunner;
    private final WorkerProperties properties;

    HlsTranscoder(ProcessRunner processRunner, WorkerProperties properties) {
        this.processRunner = processRunner;
        this.properties = properties;
    }

    TranscodeOutput transcode(Path sourceFile, Path outputDir) throws IOException {
        ProbedSource source = probeSource(sourceFile);
        double durationSeconds = source.durationSeconds();

        Path variantDir = outputDir.resolve("720p");
        Files.createDirectories(variantDir);
        Path variantPlaylist = variantDir.resolve("index.m3u8");

        List<String> command = List.of(
                properties.getFfmpegPath(),
                "-y",
                // Input-scoped hardening: both flags must precede -i to apply to
                // the input. See the class comment.
                PROTOCOL_WHITELIST.get(0), PROTOCOL_WHITELIST.get(1),
                "-f", source.formatName(),
                "-i", sourceFile.toString(),
                "-preset", "veryfast",
                "-g", "48",
                "-sc_threshold", "0",
                "-map", "0:v:0",
                "-map", "0:a:0?",
                "-c:v", "libx264",
                // Force 8-bit 4:2:0 + a browser-decodable profile regardless of the
                // source's pixel format: an untouched 10-bit/4:4:4 source (as
                // ffmpeg test patterns can be) otherwise gets carried straight
                // through into a High 4:4:4 Predictive stream that no browser's
                // MSE/H.264 decoder can play (surfaces as a black frame and an
                // hls.js "mediaSourceRequiresReset" error, not an ffmpeg failure).
                "-pix_fmt", "yuv420p",
                "-profile:v", "high",
                "-level:v", "4.0",
                "-c:a", "aac",
                "-b:a", "128k",
                "-b:v", "1200k",
                "-s:v", "1280x720",
                "-hls_time", "4",
                "-hls_playlist_type", "vod",
                "-hls_segment_filename", variantDir.resolve("segment_%03d.ts").toString(),
                variantPlaylist.toString());

        ProcessResult result = run(command, boundedTimeout(durationSeconds), outputDir);
        if (!result.succeeded()) {
            throw new TranscodeFailedException(
                    result.timedOut() ? "TRANSIENT" : "TERMINAL",
                    "ffmpeg exited " + result.exitCode() + ": " + result.stderrTail());
        }
        if (!Files.exists(variantPlaylist)) {
            throw new TranscodeFailedException("TERMINAL", "ffmpeg did not produce a variant playlist");
        }

        int segmentCount = countSegments(variantDir);
        if (segmentCount == 0) {
            throw new TranscodeFailedException("TERMINAL", "ffmpeg produced no segments");
        }

        Path masterPlaylist = outputDir.resolve("master.m3u8");
        Files.writeString(masterPlaylist, "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1328000,RESOLUTION=1280x720\n720p/index.m3u8\n");

        return new TranscodeOutput(masterPlaylist, variantPlaylist, "720p/index.m3u8", segmentCount, durationSeconds);
    }

    /**
     * Establishes both facts the transcode needs from the source — how long it is
     * and what container it actually is — in one pass, and refuses anything
     * outside {@link #ALLOWED_FORMATS} before FFmpeg is ever pointed at it.
     */
    private ProbedSource probeSource(Path sourceFile) throws IOException {
        List<String> command = List.of(
                properties.getFfprobePath(),
                "-v", "error",
                PROTOCOL_WHITELIST.get(0), PROTOCOL_WHITELIST.get(1),
                "-show_entries", "format=duration,format_name",
                // Keys, not just values: FFprobe emits these fields in its own
                // order (format_name before duration in practice), so positional
                // parsing silently swaps them.
                "-of", "default=noprint_wrappers=1",
                sourceFile.toString());
        ProcessResult result = run(command, properties.getProbeTimeout(), null);
        if (!result.succeeded()) {
            throw new TranscodeFailedException("TERMINAL", "ffprobe failed: " + result.stderrTail());
        }

        Map<String, String> fields = result.stdoutTail().lines()
                .map(line -> line.split("=", 2))
                .filter(parts -> parts.length == 2)
                .collect(Collectors.toMap(parts -> parts[0].trim(), parts -> parts[1].trim(), (first, second) -> second));

        String formatName = fields.get("format_name");
        if (formatName == null || !ALLOWED_FORMATS.contains(formatName)) {
            // Deliberately terminal, not transient: retrying will probe the same
            // bytes and reach the same answer.
            throw new TranscodeFailedException(
                    "TERMINAL", "Unsupported source container: " + formatName);
        }

        String duration = fields.get("duration");
        try {
            return new ProbedSource(Double.parseDouble(duration), formatName);
        } catch (NumberFormatException | NullPointerException e) {
            throw new TranscodeFailedException("TERMINAL", "ffprobe returned no duration");
        }
    }

    /** What the probe established about the source, carried into the transcode. */
    private record ProbedSource(double durationSeconds, String formatName) {}

    private ProcessResult run(List<String> command, Duration timeout, Path workingDirectory) throws IOException {
        return processRunner.run(command, timeout, workingDirectory);
    }

    private int countSegments(Path variantDir) throws IOException {
        try (var stream = Files.list(variantDir)) {
            return (int) stream.filter(p -> p.getFileName().toString().endsWith(".ts")).count();
        }
    }

    /** Derived from probed duration, bounded by the configured job timeout. */
    private Duration boundedTimeout(double durationSeconds) {
        Duration floor = Duration.ofSeconds(30);
        long derivedSeconds = Math.max((long) (durationSeconds * 3), floor.toSeconds());
        Duration derived = Duration.ofSeconds(derivedSeconds);
        return derived.compareTo(properties.getJobTimeout()) > 0 ? properties.getJobTimeout() : derived;
    }

    record TranscodeOutput(
            Path masterPlaylist,
            Path variantPlaylist,
            String variantRelativePath,
            int segmentCount,
            double durationSeconds) {}
}

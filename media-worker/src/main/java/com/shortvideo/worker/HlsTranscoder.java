package com.shortvideo.worker;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * FFmpeg/FFprobe mechanics (brief section 14). The <em>order</em> these run in
 * lives in {@link TranscodePipeline}; this class knows how to invoke ffmpeg
 * safely and nothing about what a job consists of. One 720p rendition for the
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

    private static final Logger log = LoggerFactory.getLogger(HlsTranscoder.class);

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

    /**
     * The bitrate ladder, widest rung first.
     *
     * <p>A single rendition gave adaptive streaming nothing to adapt to: a
     * viewer whose connection could not sustain 1200 kbps had no lower rung to
     * step down to and simply buffered. Rungs are expressed as a cap on the
     * <em>long</em> edge rather than as a height, because this app's video is
     * mostly vertical and "720p" names the wrong axis for a portrait frame.
     */
    private static final List<Rung> LADDER = List.of(
            new Rung("high", 1280, 1800, 128),
            new Rung("medium", 854, 900, 96),
            new Rung("low", 480, 400, 64));

    /** Where in the clip the poster frame is taken from, as a fraction of duration. */
    private static final double POSTER_AT = 0.25;

    static final String POSTER_FILENAME = "poster.jpg";

    /** A rung of the ladder: a cap on the long edge plus the bitrates to encode it at. */
    record Rung(String name, int maxEdge, int videoKbps, int audioKbps) {}

    /**
     * Never upscales: a rung is included only when the source is at least as
     * large as it, so a 480-pixel-tall clip is not re-encoded up to 1280 and
     * charged three times for the privilege. The narrowest rung is always kept
     * even when the source is smaller than all of them, so every video has at
     * least one rendition.
     */
    static List<Rung> ladderFor(int width, int height) {
        int sourceEdge = Math.max(width, height);
        if (sourceEdge <= 0) {
            return List.of(LADDER.get(0)); // malformed probe data; fall back to one rendition
        }
        List<Rung> chosen = LADDER.stream().filter(r -> r.maxEdge() <= sourceEdge).toList();
        return chosen.isEmpty() ? List.of(LADDER.get(LADDER.size() - 1)) : chosen;
    }

    Variant encodeRung(
            Path sourceFile, Path outputDir, ProbedSource source, Rung rung, double durationSeconds)
            throws IOException {
        Dimensions target = targetResolution(source.width(), source.height(), rung.maxEdge());
        Path variantDir = outputDir.resolve(rung.name());
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
                // A keyframe every 2s at 24fps. Segment boundaries can only fall
                // on keyframes, so this has to divide into -hls_time or the
                // rungs' segments will not line up and switching between them
                // stalls.
                "-g", "48",
                "-keyint_min", "48",
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
                "-b:a", rung.audioKbps() + "k",
                "-b:v", rung.videoKbps() + "k",
                // Capped VBR rather than a bare average: without a ceiling one
                // busy scene can spike far above the rung's advertised bandwidth,
                // which is exactly the moment a constrained viewer needed it not to.
                "-maxrate", (rung.videoKbps() * 11 / 10) + "k",
                "-bufsize", (rung.videoKbps() * 2) + "k",
                // A fixed 1280x720 stretched every portrait upload into a
                // squashed landscape frame -- this app's whole point is vertical
                // video. The target is derived from the source's own aspect
                // ratio instead, capped on the long edge by the rung.
                "-s:v", target.width() + "x" + target.height(),
                "-hls_time", "4",
                "-hls_playlist_type", "vod",
                "-hls_segment_filename", variantDir.resolve("segment_%03d.ts").toString(),
                variantPlaylist.toString());

        ProcessResult result = run(command, boundedTimeout(durationSeconds), outputDir);
        if (!result.succeeded()) {
            throw new TranscodeFailedException(
                    result.timedOut() ? "TRANSIENT" : "TERMINAL",
                    "ffmpeg exited " + result.exitCode() + " for rung " + rung.name() + ": " + result.stderrTail());
        }
        if (!Files.exists(variantPlaylist)) {
            throw new TranscodeFailedException(
                    "TERMINAL", "ffmpeg did not produce a variant playlist for rung " + rung.name());
        }

        int segmentCount = countSegments(variantDir);
        if (segmentCount == 0) {
            throw new TranscodeFailedException("TERMINAL", "ffmpeg produced no segments for rung " + rung.name());
        }

        return new Variant(rung, target, variantPlaylist, rung.name() + "/index.m3u8", segmentCount);
    }

    /**
     * A still frame for every grid and every player's {@code poster}.
     *
     * <p>Without one, the app had no thumbnails at all: search results, creator
     * profiles and favorites rendered coloured placeholder tiles, and each feed
     * slide was a black rectangle until its playback session resolved.
     *
     * <p>Taken a quarter of the way in rather than at 0s, because the first
     * frame of a real clip is very often black or a fade-in. Failure here is not
     * fatal — a video without a thumbnail is worse than one with, but far better
     * than a video that failed to process.
     *
     * @return the poster path, or null if it could not be produced.
     */
    Path extractPoster(Path sourceFile, Path outputDir, ProbedSource source, double durationSeconds) {
        Path poster = outputDir.resolve(POSTER_FILENAME);
        Dimensions target = targetResolution(source.width(), source.height(), 720);
        double seekTo = Math.max(0, durationSeconds * POSTER_AT);

        List<String> command = List.of(
                properties.getFfmpegPath(),
                "-y",
                PROTOCOL_WHITELIST.get(0), PROTOCOL_WHITELIST.get(1),
                "-f", source.formatName(),
                // Before -i: seeking on the input side is a keyframe seek and
                // costs nothing, where an output-side seek decodes every frame up
                // to that point.
                "-ss", String.format(Locale.ROOT, "%.3f", seekTo),
                "-i", sourceFile.toString(),
                "-frames:v", "1",
                "-vf", "scale=" + target.width() + ":" + target.height(),
                "-q:v", "4",
                poster.toString());

        try {
            ProcessResult result = run(command, properties.getProbeTimeout(), outputDir);
            if (result.succeeded() && Files.exists(poster) && Files.size(poster) > 0) {
                return poster;
            }
            log.warn("Poster extraction produced no image; continuing without a thumbnail");
        } catch (IOException | RuntimeException e) {
            log.warn("Poster extraction failed; continuing without a thumbnail: {}", e.getMessage());
        }
        try {
            Files.deleteIfExists(poster);
        } catch (IOException ignored) {
            // A zero-byte leftover would be uploaded as a broken image.
        }
        return null;
    }

    /**
     * Written by hand rather than by ffmpeg, because each rung is a separate
     * invocation. Separate passes cost one decode per rung, which is the price
     * of keeping optional audio ({@code -map 0:a:0?}) working and of letting one
     * rung fail without taking the others' output with it — a single-pass
     * {@code split} filter with {@code -var_stream_map} cannot express an audio
     * stream that might not be there.
     */
    Path writeMasterPlaylist(Path outputDir, List<Variant> variants) throws IOException {
        StringBuilder master = new StringBuilder("#EXTM3U\n#EXT-X-VERSION:3\n");
        for (Variant variant : variants) {
            // BANDWIDTH is the peak the player must sustain, so it has to include
            // audio and container overhead -- advertising the video bitrate alone
            // makes a player pick a rung it cannot actually keep up with.
            int bandwidth = (variant.rung().videoKbps() + variant.rung().audioKbps()) * 1000 * 11 / 10;
            master.append("#EXT-X-STREAM-INF:BANDWIDTH=")
                    .append(bandwidth)
                    .append(",RESOLUTION=")
                    .append(variant.dimensions().width())
                    .append("x")
                    .append(variant.dimensions().height())
                    .append("\n")
                    .append(variant.relativePlaylistPath())
                    .append("\n");
        }
        Path masterPlaylist = outputDir.resolve("master.m3u8");
        Files.writeString(masterPlaylist, master.toString());
        return masterPlaylist;
    }

    /** One encoded rendition on disk. */
    record Variant(
            Rung rung, Dimensions dimensions, Path playlist, String relativePlaylistPath, int segmentCount) {}

    /**
     * Scales so the longer edge is at most {@code maxEdge}, preserving the
     * source's own aspect ratio -- landscape sources land at 1280x720-ish,
     * portrait ones at 720x1280-ish, instead of every upload being force-fit
     * into one landscape frame. Both edges are rounded down to even numbers,
     * which libx264's 4:2:0 chroma subsampling requires.
     *
     * <p>Never scales up: a source already smaller than the rung keeps its own
     * size, so a small clip is not blown up and re-encoded at a bitrate its
     * detail cannot justify.
     */
    static Dimensions targetResolution(int sourceWidth, int sourceHeight, int maxEdge) {
        if (sourceWidth <= 0 || sourceHeight <= 0) {
            return new Dimensions(1280, 720); // malformed probe data; fall back to the old fixed frame
        }
        double scale = Math.min(1.0, (double) maxEdge / Math.max(sourceWidth, sourceHeight));
        int width = evenFloor((int) Math.round(sourceWidth * scale));
        int height = evenFloor((int) Math.round(sourceHeight * scale));
        return new Dimensions(Math.max(width, 2), Math.max(height, 2));
    }

    private static int evenFloor(int value) {
        return value % 2 == 0 ? value : value - 1;
    }

    record Dimensions(int width, int height) {}

    /**
     * Establishes both facts the transcode needs from the source — how long it is
     * and what container it actually is — in one pass, and refuses anything
     * outside {@link #ALLOWED_FORMATS} before FFmpeg is ever pointed at it.
     */
    ProbedSource probeSource(Path sourceFile) throws IOException {
        List<String> command = List.of(
                properties.getFfprobePath(),
                "-v", "error",
                PROTOCOL_WHITELIST.get(0), PROTOCOL_WHITELIST.get(1),
                "-select_streams", "v:0",
                "-show_entries", "format=duration,format_name:stream=width,height",
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
            // Missing width/height (e.g. an audio-only file smuggled past the
            // container check) falls back to 0x0 -- targetResolution treats that
            // as malformed and keeps the old fixed frame rather than failing the job.
            int width = parseIntOrZero(fields.get("width"));
            int height = parseIntOrZero(fields.get("height"));
            double durationSeconds = Double.parseDouble(duration);
            // A "short-video platform" with no ceiling on length will happily
            // accept and transcode a feature film -- three times over, now that
            // there is a ladder. Terminal: the same bytes will be the same
            // length on every retry.
            long maxSeconds = properties.getMaxDurationSeconds();
            if (maxSeconds > 0 && durationSeconds > maxSeconds) {
                throw new TranscodeFailedException(
                        "TERMINAL",
                        "Source is " + Math.round(durationSeconds) + "s; the limit is " + maxSeconds + "s");
            }
            return new ProbedSource(durationSeconds, formatName, width, height);
        } catch (NumberFormatException | NullPointerException e) {
            throw new TranscodeFailedException("TERMINAL", "ffprobe returned no duration");
        }
    }

    private static int parseIntOrZero(String value) {
        try {
            return value == null ? 0 : Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** What the probe established about the source, carried into the transcode. */
    record ProbedSource(double durationSeconds, String formatName, int width, int height) {}

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

    /**
     * @param variantRelativePath the widest rung, kept for callers that still
     *     expect a single "the" variant.
     * @param variantRelativePaths every rung, in the order they appear in the
     *     master playlist.
     * @param posterRelativePath the thumbnail, or null when extraction failed --
     *     a missing thumbnail never fails an otherwise good transcode.
     */
    record TranscodeOutput(
            Path masterPlaylist,
            Path variantPlaylist,
            String variantRelativePath,
            int segmentCount,
            double durationSeconds,
            List<String> variantRelativePaths,
            String posterRelativePath) {}
}

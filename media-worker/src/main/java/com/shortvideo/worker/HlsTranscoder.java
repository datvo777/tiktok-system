package com.shortvideo.worker;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * FFmpeg/FFprobe orchestration (brief section 14). One 720p rendition for the
 * first version; the master playlist is hand-written so even a single rendition
 * has a proper master/variant structure.
 */
@Component
class HlsTranscoder {

    private final ProcessRunner processRunner;
    private final WorkerProperties properties;

    HlsTranscoder(ProcessRunner processRunner, WorkerProperties properties) {
        this.processRunner = processRunner;
        this.properties = properties;
    }

    TranscodeOutput transcode(Path sourceFile, Path outputDir) throws IOException {
        double durationSeconds = probeDuration(sourceFile);

        Path variantDir = outputDir.resolve("720p");
        Files.createDirectories(variantDir);
        Path variantPlaylist = variantDir.resolve("index.m3u8");

        List<String> command = List.of(
                properties.getFfmpegPath(),
                "-y",
                "-i", sourceFile.toString(),
                "-preset", "veryfast",
                "-g", "48",
                "-sc_threshold", "0",
                "-map", "0:v:0",
                "-map", "0:a:0?",
                "-c:v", "libx264",
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

    private double probeDuration(Path sourceFile) throws IOException {
        List<String> command = List.of(
                properties.getFfprobePath(),
                "-v", "error",
                "-show_entries", "format=duration",
                "-of", "default=noprint_wrappers=1:nokey=1",
                sourceFile.toString());
        ProcessResult result = run(command, properties.getProbeTimeout(), null);
        if (!result.succeeded()) {
            throw new TranscodeFailedException("TERMINAL", "ffprobe failed: " + result.stderrTail());
        }
        try {
            return Double.parseDouble(result.stdoutTail().trim());
        } catch (NumberFormatException e) {
            throw new TranscodeFailedException("TERMINAL", "ffprobe returned no duration");
        }
    }

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

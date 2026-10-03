package com.shortvideo.worker;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "shortvideo.worker")
public class WorkerProperties {

    /** A laptop transcodes one or two streams before everything else suffers. */
    private int maxConcurrentJobs = 2;
    private Duration jobTimeout = Duration.ofMinutes(15);
    private Duration probeTimeout = Duration.ofSeconds(30);
    private Duration killGrace = Duration.ofSeconds(10);
    private String ffmpegPath = "ffmpeg";
    private String ffprobePath = "ffprobe";
    private long maxSourceBytes = 500L * 1024 * 1024;

    /**
     * Ceiling on source duration, in seconds. Zero disables the check. Default
     * ten minutes: this is a short-video platform, and without a cap one upload
     * could occupy a worker slot for hours -- three times over, once the ladder
     * encodes each rung separately.
     */
    private long maxDurationSeconds = 600;

    /**
     * Ceiling on the source's longer edge, in pixels. Zero disables the check. Default
     * 8K: size and duration are already capped, but neither bounds the frame size, and
     * a small file can still declare a frame large enough to make FFmpeg allocate
     * gigabytes per frame buffer while it decodes.
     */
    private int maxSourceEdge = 7680;

    public int getMaxConcurrentJobs() { return maxConcurrentJobs; }
    public void setMaxConcurrentJobs(int maxConcurrentJobs) { this.maxConcurrentJobs = maxConcurrentJobs; }
    public Duration getJobTimeout() { return jobTimeout; }
    public void setJobTimeout(Duration jobTimeout) { this.jobTimeout = jobTimeout; }
    public Duration getProbeTimeout() { return probeTimeout; }
    public void setProbeTimeout(Duration probeTimeout) { this.probeTimeout = probeTimeout; }
    public Duration getKillGrace() { return killGrace; }
    public void setKillGrace(Duration killGrace) { this.killGrace = killGrace; }
    public String getFfmpegPath() { return ffmpegPath; }
    public void setFfmpegPath(String ffmpegPath) { this.ffmpegPath = ffmpegPath; }
    public String getFfprobePath() { return ffprobePath; }
    public void setFfprobePath(String ffprobePath) { this.ffprobePath = ffprobePath; }
    public long getMaxDurationSeconds() { return maxDurationSeconds; }
    public void setMaxDurationSeconds(long maxDurationSeconds) { this.maxDurationSeconds = maxDurationSeconds; }
    public int getMaxSourceEdge() { return maxSourceEdge; }
    public void setMaxSourceEdge(int maxSourceEdge) { this.maxSourceEdge = maxSourceEdge; }
    public long getMaxSourceBytes() { return maxSourceBytes; }
    public void setMaxSourceBytes(long maxSourceBytes) { this.maxSourceBytes = maxSourceBytes; }
}

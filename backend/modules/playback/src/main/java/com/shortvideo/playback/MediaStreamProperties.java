package com.shortvideo.playback;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "shortvideo.media")
public class MediaStreamProperties {

    /** Copy-loop buffer size (brief section 8: 64-128 KB). */
    private int streamBufferBytes = 131072;

    /** Bounds concurrent gateway streams so a stalled client cannot exhaust the MinIO pool. */
    private int maxConcurrentStreams = 32;

    public int getStreamBufferBytes() { return streamBufferBytes; }
    public void setStreamBufferBytes(int streamBufferBytes) { this.streamBufferBytes = streamBufferBytes; }
    public int getMaxConcurrentStreams() { return maxConcurrentStreams; }
    public void setMaxConcurrentStreams(int maxConcurrentStreams) { this.maxConcurrentStreams = maxConcurrentStreams; }
}

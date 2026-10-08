package com.shortvideo.notification.realtime;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The realtime stream is an experiment next to polling, which stays the default: off unless
 * {@code shortvideo.realtime.enabled=true}. Nothing the stream carries is authoritative, so
 * turning it off (or losing it) only makes the client fall back to polling.
 */
@ConfigurationProperties(prefix = "shortvideo.realtime")
public class RealtimeProperties {

    private boolean enabled = false;
    /** A fourth connection from one account closes its oldest, so a tab left open cannot pin slots. */
    private int maxConnectionsPerAccount = 3;
    /** Across all accounts; past it the endpoint answers 503 and the client polls instead. */
    private int maxConnections = 5000;
    /** Shorter than the 30 minute token, so a client reconnects with a credential that is still good. */
    private Duration emitterTimeout = Duration.ofMinutes(25);
    /** Spreads reconnects: without it every stream opened together would expire together. */
    private Duration emitterTimeoutJitter = Duration.ofMinutes(1);
    /** Keeps the connection alive through proxies and finds sockets that have died. */
    private Duration heartbeatInterval = Duration.ofSeconds(20);
    /** How often each open stream is checked against the account's current standing. */
    private Duration revalidateInterval = Duration.ofSeconds(60);
    private Duration retryAfter = Duration.ofSeconds(30);
    /**
     * How many times one account may open a stream per {@code openWindow}; zero turns it off. The
     * connection limits above bound what is open, not how fast a client cycles through opening.
     */
    private int maxOpensPerWindow = 30;
    private Duration openWindow = Duration.ofMinutes(1);

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getMaxConnectionsPerAccount() { return maxConnectionsPerAccount; }
    public void setMaxConnectionsPerAccount(int v) { this.maxConnectionsPerAccount = v; }
    public int getMaxConnections() { return maxConnections; }
    public void setMaxConnections(int v) { this.maxConnections = v; }
    public Duration getEmitterTimeout() { return emitterTimeout; }
    public void setEmitterTimeout(Duration v) { this.emitterTimeout = v; }
    public Duration getEmitterTimeoutJitter() { return emitterTimeoutJitter; }
    public void setEmitterTimeoutJitter(Duration v) { this.emitterTimeoutJitter = v; }
    public Duration getHeartbeatInterval() { return heartbeatInterval; }
    public void setHeartbeatInterval(Duration v) { this.heartbeatInterval = v; }
    public Duration getRevalidateInterval() { return revalidateInterval; }
    public void setRevalidateInterval(Duration v) { this.revalidateInterval = v; }
    public Duration getRetryAfter() { return retryAfter; }
    public void setRetryAfter(Duration v) { this.retryAfter = v; }
    public int getMaxOpensPerWindow() { return maxOpensPerWindow; }
    public void setMaxOpensPerWindow(int v) { this.maxOpensPerWindow = v; }
    public Duration getOpenWindow() { return openWindow; }
    public void setOpenWindow(Duration v) { this.openWindow = v; }
}

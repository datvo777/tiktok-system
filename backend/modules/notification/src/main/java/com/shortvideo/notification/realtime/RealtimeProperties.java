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
    private Duration emitterTimeout = Duration.ofMinutes(20);
    /** Spreads reconnects: without it every stream opened together would expire together. */
    private Duration emitterTimeoutJitter = Duration.ofMinutes(8);
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
    /**
     * How many new streams the instance will start authenticating per second, however many accounts
     * ask. After a restart every client returns at once and each open costs database work, so the
     * rest are refused immediately, before they can queue for a connection; see {@code StreamOpenGate}.
     */
    private int maxOpensPerSecond = 200;
    /** Opens allowed in a burst above the steady rate. */
    private int openBurst = 400;
    /**
     * Opens in their authentication phase at the same moment. Bounded well under the connection pool,
     * so a wave of reconnects cannot take every connection from REST and the media gateway.
     */
    private int maxConcurrentOpens = 6;
    /** A refused client is told to come back in 1 s up to this, widening as the refusals pile up. */
    private Duration retryAfterMax = Duration.ofSeconds(15);
    /** The wait each stream tells its client to use for an automatic reconnect: random, per stream. */
    private Duration reconnectMin = Duration.ofSeconds(5);
    private Duration reconnectMax = Duration.ofSeconds(20);
    /** On shutdown, how far apart clients are told to reconnect. */
    private Duration shutdownReconnectMax = Duration.ofSeconds(30);

    public int getMaxOpensPerSecond() { return maxOpensPerSecond; }
    public void setMaxOpensPerSecond(int v) { this.maxOpensPerSecond = v; }
    public int getOpenBurst() { return openBurst; }
    public void setOpenBurst(int v) { this.openBurst = v; }
    public int getMaxConcurrentOpens() { return maxConcurrentOpens; }
    public void setMaxConcurrentOpens(int v) { this.maxConcurrentOpens = v; }
    public Duration getRetryAfterMax() { return retryAfterMax; }
    public void setRetryAfterMax(Duration v) { this.retryAfterMax = v; }
    public Duration getReconnectMin() { return reconnectMin; }
    public void setReconnectMin(Duration v) { this.reconnectMin = v; }
    public Duration getReconnectMax() { return reconnectMax; }
    public void setReconnectMax(Duration v) { this.reconnectMax = v; }
    public Duration getShutdownReconnectMax() { return shutdownReconnectMax; }
    public void setShutdownReconnectMax(Duration v) { this.shutdownReconnectMax = v; }
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

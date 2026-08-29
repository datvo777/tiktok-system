package com.shortvideo.shared.outbox;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Relay tuning (brief section 11, "Outbox relay producer configuration").
 *
 * <p>The claim lease must exceed the producer delivery deadline plus a
 * finalisation margin, or the relay must renew the lease. Defaults here are
 * 60s lease against a 30s delivery timeout.
 */
@ConfigurationProperties(prefix = "shortvideo.outbox")
public class OutboxProperties {

    private boolean enabled = true;
    private String relayId = "relay-1";
    private int batchSize = 100;
    private Duration lease = Duration.ofSeconds(60);
    private Duration pollInterval = Duration.ofMillis(500);
    private Duration publishTimeout = Duration.ofSeconds(30);
    private Duration maxBackoff = Duration.ofMinutes(5);
    private int maxAttempts = 10;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getRelayId() { return relayId; }
    public void setRelayId(String relayId) { this.relayId = relayId; }
    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
    public Duration getLease() { return lease; }
    public void setLease(Duration lease) { this.lease = lease; }
    public Duration getPollInterval() { return pollInterval; }
    public void setPollInterval(Duration pollInterval) { this.pollInterval = pollInterval; }
    public Duration getPublishTimeout() { return publishTimeout; }
    public void setPublishTimeout(Duration publishTimeout) { this.publishTimeout = publishTimeout; }
    public Duration getMaxBackoff() { return maxBackoff; }
    public void setMaxBackoff(Duration maxBackoff) { this.maxBackoff = maxBackoff; }
    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
}

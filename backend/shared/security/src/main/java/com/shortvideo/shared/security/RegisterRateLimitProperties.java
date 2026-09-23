package com.shortvideo.shared.security;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Thresholds for {@link RegisterRateLimiter}. Looser than the login IP window
 * ({@link LoginRateLimitProperties}) since a real signup burst from one IP
 * (a shared campus or office network) is more plausible than twenty logins in
 * a minute, but still tight enough to make bulk email-enumeration through
 * this endpoint slow to run.
 */
@ConfigurationProperties(prefix = "shortvideo.register-rate-limit")
public class RegisterRateLimitProperties {

    private boolean enabled = true;
    private int maxAttemptsPerIp = 30;
    private Duration ipWindow = Duration.ofMinutes(1);

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getMaxAttemptsPerIp() { return maxAttemptsPerIp; }
    public void setMaxAttemptsPerIp(int maxAttemptsPerIp) { this.maxAttemptsPerIp = maxAttemptsPerIp; }
    public Duration getIpWindow() { return ipWindow; }
    public void setIpWindow(Duration ipWindow) { this.ipWindow = ipWindow; }
}

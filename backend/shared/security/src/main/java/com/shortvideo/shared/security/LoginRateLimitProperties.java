package com.shortvideo.shared.security;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Thresholds for {@link LoginRateLimiter}. The defaults are loose enough that a
 * person fumbling a password never notices, and tight enough that automated
 * guessing is uneconomic: 20 attempts per IP per minute bounds sustained BCrypt
 * cost to well under one core, and 10 failures per account per 15 minutes leaves
 * a brute-forcer needing centuries for anything with real entropy.
 */
@ConfigurationProperties(prefix = "shortvideo.login-rate-limit")
public class LoginRateLimitProperties {

    private boolean enabled = true;
    private int maxAttemptsPerIp = 20;
    private Duration ipWindow = Duration.ofMinutes(1);
    private int maxFailuresPerAccount = 10;
    private Duration accountWindow = Duration.ofMinutes(15);

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getMaxAttemptsPerIp() { return maxAttemptsPerIp; }
    public void setMaxAttemptsPerIp(int maxAttemptsPerIp) { this.maxAttemptsPerIp = maxAttemptsPerIp; }
    public Duration getIpWindow() { return ipWindow; }
    public void setIpWindow(Duration ipWindow) { this.ipWindow = ipWindow; }
    public int getMaxFailuresPerAccount() { return maxFailuresPerAccount; }
    public void setMaxFailuresPerAccount(int maxFailuresPerAccount) { this.maxFailuresPerAccount = maxFailuresPerAccount; }
    public Duration getAccountWindow() { return accountWindow; }
    public void setAccountWindow(Duration accountWindow) { this.accountWindow = accountWindow; }
}

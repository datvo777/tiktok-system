package com.shortvideo.shared.security;

import java.time.Duration;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Second-factor sign-in (docs/mfa-plan.md). Off by default: with {@code enabled=false} the login flow,
 * the filter and {@code /internal} behave exactly as they did before this existed.
 */
@ConfigurationProperties(prefix = "shortvideo.mfa")
public class MfaProperties {

    private boolean enabled = false;
    /**
     * Roles that must hold a second factor to reach {@code /internal}. Anyone with an active factor is asked
     * for a code at sign-in regardless; this only decides who is shut out of the admin API without one.
     */
    private Set<String> requiredRoles = Set.of("ADMIN");
    /** Shown in the authenticator app next to the account. */
    private String issuer = "Short";
    /** How long the password-only step stays valid for the code to be entered. */
    private Duration pendingTtl = Duration.ofMinutes(5);
    /** Wrong codes one pending sign-in may try before it is burned and the password must be entered again. */
    private int maxAttemptsPerToken = 5;
    /** Wrong codes per account within {@link #accountWindow} before further attempts are refused. */
    private int maxFailuresPerAccount = 10;
    private Duration accountWindow = Duration.ofMinutes(15);
    /** Attempts per client address within {@link #ipWindow}, every one counted. */
    private int maxAttemptsPerIp = 30;
    private Duration ipWindow = Duration.ofMinutes(15);
    /** Base64 of the 32-byte AES key that protects the stored TOTP secrets. Required when enabled. */
    private String encryptionKey;
    /** Stored beside each secret, so a later key rotation can tell which key sealed it. */
    private String encryptionKeyId = "k1";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Set<String> getRequiredRoles() { return requiredRoles; }
    public void setRequiredRoles(Set<String> requiredRoles) { this.requiredRoles = requiredRoles; }
    public String getIssuer() { return issuer; }
    public void setIssuer(String issuer) { this.issuer = issuer; }
    public Duration getPendingTtl() { return pendingTtl; }
    public void setPendingTtl(Duration pendingTtl) { this.pendingTtl = pendingTtl; }
    public int getMaxAttemptsPerToken() { return maxAttemptsPerToken; }
    public void setMaxAttemptsPerToken(int v) { this.maxAttemptsPerToken = v; }
    public int getMaxFailuresPerAccount() { return maxFailuresPerAccount; }
    public void setMaxFailuresPerAccount(int v) { this.maxFailuresPerAccount = v; }
    public Duration getAccountWindow() { return accountWindow; }
    public void setAccountWindow(Duration accountWindow) { this.accountWindow = accountWindow; }
    public int getMaxAttemptsPerIp() { return maxAttemptsPerIp; }
    public void setMaxAttemptsPerIp(int v) { this.maxAttemptsPerIp = v; }
    public Duration getIpWindow() { return ipWindow; }
    public void setIpWindow(Duration ipWindow) { this.ipWindow = ipWindow; }
    public String getEncryptionKey() { return encryptionKey; }
    public void setEncryptionKey(String encryptionKey) { this.encryptionKey = encryptionKey; }
    public String getEncryptionKeyId() { return encryptionKeyId; }
    public void setEncryptionKeyId(String encryptionKeyId) { this.encryptionKeyId = encryptionKeyId; }
}

package com.shortvideo.shared.security;

import java.time.Duration;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "shortvideo.jwt")
public class JwtProperties {

    /** HS256 requires >= 32 bytes. Validated at startup. */
    private String secret;
    private String issuer = "short-video-local";
    private String audience = "short-video-web";
    private Duration ttl = Duration.ofMinutes(30);
    /**
     * The longest a session can last from the login, however often it is renewed. {@link #ttl} only
     * bounds an idle gap (a token must be renewed within it); without this, a session renewed every
     * few minutes never ends.
     */
    private Duration absoluteTtl = Duration.ofDays(30);
    /** The same bound for accounts holding any of {@link #privilegedRoles}: far shorter, because far more is at stake. */
    private Duration privilegedAbsoluteTtl = Duration.ofHours(12);
    private Set<String> privilegedRoles = Set.of("ADMIN");
    private String sessionCookieName = "sv_session";
    /**
     * Defaults to true so that shipping a session cookie over plain HTTP is an
     * explicit decision. The {@code local} profile opts out; nothing else should.
     */
    private boolean cookieSecure = true;
    /**
     * Permits the published development signing key. Set only by the {@code local}
     * and {@code test} profiles — see {@link JwtService#requireUsableSecret}.
     */
    private boolean allowInsecureSecret = false;
    /**
     * How long a revoked token's {@code jti} is remembered after logout. Only needs
     * to cover the longest possible remaining lifetime of an issued token, so it
     * tracks {@link #ttl} rather than being tuned separately.
     */
    public Duration revocationRetention() {
        return ttl.plusMinutes(1);
    }

    public String getSecret() { return secret; }
    public void setSecret(String secret) { this.secret = secret; }
    public String getIssuer() { return issuer; }
    public void setIssuer(String issuer) { this.issuer = issuer; }
    public String getAudience() { return audience; }
    public void setAudience(String audience) { this.audience = audience; }
    public Duration getTtl() { return ttl; }
    public void setTtl(Duration ttl) { this.ttl = ttl; }
    public Duration getAbsoluteTtl() { return absoluteTtl; }
    public void setAbsoluteTtl(Duration absoluteTtl) { this.absoluteTtl = absoluteTtl; }
    public Duration getPrivilegedAbsoluteTtl() { return privilegedAbsoluteTtl; }
    public void setPrivilegedAbsoluteTtl(Duration privilegedAbsoluteTtl) { this.privilegedAbsoluteTtl = privilegedAbsoluteTtl; }
    public Set<String> getPrivilegedRoles() { return privilegedRoles; }
    public void setPrivilegedRoles(Set<String> privilegedRoles) { this.privilegedRoles = privilegedRoles; }
    public String getSessionCookieName() { return sessionCookieName; }
    public void setSessionCookieName(String sessionCookieName) { this.sessionCookieName = sessionCookieName; }
    public boolean isCookieSecure() { return cookieSecure; }
    public void setCookieSecure(boolean cookieSecure) { this.cookieSecure = cookieSecure; }
    public boolean isAllowInsecureSecret() { return allowInsecureSecret; }
    public void setAllowInsecureSecret(boolean allowInsecureSecret) { this.allowInsecureSecret = allowInsecureSecret; }
}

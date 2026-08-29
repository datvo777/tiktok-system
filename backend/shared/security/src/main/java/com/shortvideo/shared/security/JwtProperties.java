package com.shortvideo.shared.security;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "shortvideo.jwt")
public class JwtProperties {

    /** HS256 requires >= 32 bytes. Validated at startup. */
    private String secret;
    private String issuer = "short-video-local";
    private String audience = "short-video-web";
    private Duration ttl = Duration.ofMinutes(30);
    private String sessionCookieName = "sv_session";
    /** Local development runs over plain HTTP; set true for anything beyond localhost. */
    private boolean cookieSecure = false;

    public String getSecret() { return secret; }
    public void setSecret(String secret) { this.secret = secret; }
    public String getIssuer() { return issuer; }
    public void setIssuer(String issuer) { this.issuer = issuer; }
    public String getAudience() { return audience; }
    public void setAudience(String audience) { this.audience = audience; }
    public Duration getTtl() { return ttl; }
    public void setTtl(Duration ttl) { this.ttl = ttl; }
    public String getSessionCookieName() { return sessionCookieName; }
    public void setSessionCookieName(String sessionCookieName) { this.sessionCookieName = sessionCookieName; }
    public boolean isCookieSecure() { return cookieSecure; }
    public void setCookieSecure(boolean cookieSecure) { this.cookieSecure = cookieSecure; }
}

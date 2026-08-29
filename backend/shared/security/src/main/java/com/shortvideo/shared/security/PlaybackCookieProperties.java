package com.shortvideo.shared.security;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "shortvideo.playback")
public class PlaybackCookieProperties {

    private String cookieName = "sv_playback";
    private Duration ttl = Duration.ofSeconds(300);
    /** Local development runs over plain HTTP; set true for anything beyond localhost. */
    private boolean cookieSecure = false;

    public String getCookieName() { return cookieName; }
    public void setCookieName(String cookieName) { this.cookieName = cookieName; }
    public Duration getTtl() { return ttl; }
    public void setTtl(Duration ttl) { this.ttl = ttl; }
    public boolean isCookieSecure() { return cookieSecure; }
    public void setCookieSecure(boolean cookieSecure) { this.cookieSecure = cookieSecure; }
}

package com.shortvideo.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

/**
 * X-Forwarded-For is append-on-the-right: each trusted hop appends its view of
 * the immediate peer, so a client can prepend arbitrarily many fake entries
 * but cannot control what gets appended after them. The rightmost entry (minus
 * however many hops are genuinely trusted) is the one address a caller cannot
 * forge -- reading from the left, as an earlier version of this class did,
 * reads back exactly the attacker-controlled text instead.
 */
class ClientIpResolverTest {

    private static HttpServletRequest requestWith(String forwardedFor, String remoteAddr) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("X-Forwarded-For")).thenReturn(forwardedFor);
        when(request.getRemoteAddr()).thenReturn(remoteAddr);
        return request;
    }

    @Test
    void ignoresTheHeaderEntirelyWhenNoProxyIsTrusted() {
        ClientIpResolver resolver = new ClientIpResolver(false, 1);
        HttpServletRequest request = requestWith("9.9.9.9", "10.0.0.1");

        assertThat(resolver.resolve(request)).isEqualTo("10.0.0.1");
    }

    @Test
    void withOneTrustedHopTakesTheRightmostEntryNotTheLeftmost() {
        ClientIpResolver resolver = new ClientIpResolver(true, 1);
        // Left entry is attacker-supplied text; the right one is what our own
        // trusted proxy actually appended.
        HttpServletRequest request = requestWith("1.2.3.4, 9.9.9.9", "10.0.0.1");

        assertThat(resolver.resolve(request)).isEqualTo("9.9.9.9");
    }

    /** Two trusted hops (e.g. a CDN in front of an internal load balancer). */
    @Test
    void countsBackFromTheRightByTheConfiguredHopCount() {
        ClientIpResolver resolver = new ClientIpResolver(true, 2);
        // client-claimed, real-client-as-seen-by-cdn, cdn-as-seen-by-lb
        HttpServletRequest request = requestWith("1.2.3.4, 9.9.9.9, 8.8.8.8", "10.0.0.1");

        assertThat(resolver.resolve(request)).isEqualTo("9.9.9.9");
    }

    /**
     * A caller who rewrites the header on every request must not be able to
     * land in a fresh rate-limit bucket each time -- the whole reason this class
     * exists is to make that impossible once a proxy is trusted.
     */
    @Test
    void varyingTheClientSuppliedPrefixDoesNotChangeTheResolvedAddress() {
        ClientIpResolver resolver = new ClientIpResolver(true, 1);
        HttpServletRequest first = requestWith("1.1.1.1, 9.9.9.9", "10.0.0.1");
        HttpServletRequest second = requestWith("totally-different-garbage, 9.9.9.9", "10.0.0.1");

        assertThat(resolver.resolve(first)).isEqualTo(resolver.resolve(second));
    }

    @Test
    void fallsBackToTheSocketAddressWhenFewerHopsArePresentThanConfigured() {
        ClientIpResolver resolver = new ClientIpResolver(true, 3);
        HttpServletRequest request = requestWith("1.2.3.4, 9.9.9.9", "10.0.0.1");

        assertThat(resolver.resolve(request)).isEqualTo("10.0.0.1");
    }

    @Test
    void fallsBackToTheSocketAddressWhenTheHeaderIsAbsent() {
        ClientIpResolver resolver = new ClientIpResolver(true, 1);
        HttpServletRequest request = requestWith(null, "10.0.0.1");

        assertThat(resolver.resolve(request)).isEqualTo("10.0.0.1");
    }
}

package com.shortvideo.notification.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.ServletException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class StreamOpenGateFilterTest {

    private final RealtimeProperties properties = new RealtimeProperties();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    private StreamOpenGateFilter filter() {
        return new StreamOpenGateFilter(new StreamOpenGate(properties, meters));
    }

    private MockHttpServletRequest stream() {
        return new MockHttpServletRequest("GET", StreamOpenGateFilter.PATH);
    }

    @Test
    void admitsAndPassesTheRequestOn() throws Exception {
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter().doFilter(stream(), response, chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void turnsARefusedOpenAwayWithRetryAfterWithoutTouchingTheChain() throws Exception {
        properties.setOpenBurst(0);
        properties.setMaxOpensPerSecond(1);
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter().doFilter(stream(), response, chain);

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getHeader("Retry-After")).isNotNull();
        assertThat(chain.getRequest()).as("authentication never ran").isNull();
    }

    @Test
    void leavesEveryOtherRequestAlone() throws Exception {
        properties.setOpenBurst(0);
        properties.setMaxOpensPerSecond(1);
        var filter = filter();

        for (var request : new MockHttpServletRequest[] {
            new MockHttpServletRequest("GET", "/api/v1/notifications"),
            new MockHttpServletRequest("POST", StreamOpenGateFilter.PATH),
        }) {
            var chain = new MockFilterChain();
            filter.doFilter(request, new MockHttpServletResponse(), chain);
            assertThat(chain.getRequest()).isNotNull();
        }
    }

    @Test
    void givesThePermitBackEvenWhenTheChainThrows() throws Exception {
        properties.setMaxConcurrentOpens(1);
        var filter = filter();
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> filter.doFilter(stream(), new MockHttpServletResponse(), (req, res) -> {
                    calls.incrementAndGet();
                    throw new ServletException("boom");
                }))
                .isInstanceOf(ServletException.class);
        var chain = new MockFilterChain();
        filter.doFilter(stream(), new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).as("the single permit was released").isNotNull();
        assertThat(calls).hasValue(1);
    }
}

package com.shortvideo.playback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

class MediaGatewayControllerTest {

    private static final String VIDEO_ID = "3f2504e0-4f89-41d3-9a0c-0305e82c3301";

    private final MediaAuthorizer authorizer = mock(MediaAuthorizer.class);
    private final MediaObjectStreamer streamer = mock(MediaObjectStreamer.class);
    private final MediaGatewayController controller =
            new MediaGatewayController(new MediaPathValidator(), authorizer, streamer);

    /**
     * /media/** is open to signed-out viewers, who are identified by a device cookie and reach the
     * controller with no principal. Reading the account id from that null for the log line made every
     * segment they fetched a 500, whatever the log level.
     */
    @Test
    void servesASignedOutViewerWithoutDereferencingThePrincipal() {
        when(streamer.prepare(any(), isNull()))
                .thenReturn(new MediaObjectStreamer.PreparedStream(out -> {}, 12L, false, null));

        var response = controller.asset(VIDEO_ID, "1", "/master.m3u8", null, new MockHttpServletRequest());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentLength()).isEqualTo(12L);
    }
}

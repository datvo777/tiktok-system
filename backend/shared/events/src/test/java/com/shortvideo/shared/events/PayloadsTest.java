package com.shortvideo.shared.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PayloadsTest {

    @Test
    void aPresentFieldIsReturnedAsText() {
        assertThat(Payloads.required(Map.of("videoId", "v1", "n", 7), "videoId")).isEqualTo("v1");
        assertThat(Payloads.required(Map.of("n", 7), "n")).isEqualTo("7");
    }

    /** Not "null": a missing id must not become a row keyed by the word null. */
    @Test
    void aMissingFieldIsRefusedNotStoredAsTheTextNull() {
        assertThatThrownBy(() -> Payloads.required(Map.of(), "videoId"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("videoId");
    }

    @Test
    void anExplicitNullIsRefusedToo() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("videoId", null);

        assertThatThrownBy(() -> Payloads.required(payload, "videoId")).isInstanceOf(IllegalArgumentException.class);
    }
}

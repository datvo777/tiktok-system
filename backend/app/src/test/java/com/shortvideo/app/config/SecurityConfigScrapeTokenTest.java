package com.shortvideo.app.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SecurityConfigScrapeTokenTest {

    @Test
    void emptyTokenDisablesTokenScraping() {
        assertThatCode(() -> SecurityConfig.requireUsableScrapeToken("", false)).doesNotThrowAnyException();
    }

    @Test
    void shortTokenIsRefusedEverywhere() {
        assertThatThrownBy(() -> SecurityConfig.requireUsableScrapeToken("too-short", true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 32");
    }

    @Test
    void publishedSampleIsRefusedOutsideLocalProfiles() {
        assertThatThrownBy(() -> SecurityConfig.requireUsableScrapeToken(SecurityConfig.SAMPLE_SCRAPE_TOKEN, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(".env.example");
    }

    @Test
    void publishedSampleIsAllowedLocally() {
        assertThatCode(() -> SecurityConfig.requireUsableScrapeToken(SecurityConfig.SAMPLE_SCRAPE_TOKEN, true))
                .doesNotThrowAnyException();
    }

    @Test
    void aRealTokenIsAccepted() {
        assertThatCode(() -> SecurityConfig.requireUsableScrapeToken("a".repeat(64), false)).doesNotThrowAnyException();
    }
}

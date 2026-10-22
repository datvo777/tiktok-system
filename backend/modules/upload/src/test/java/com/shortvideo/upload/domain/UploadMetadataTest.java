package com.shortvideo.upload.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class UploadMetadataTest {

    @Test
    void titleIsTrimmedAndNormalisedToNfc() {
        // "e" + combining acute vs the precomposed character: same text, one stored form.
        assertThat(UploadMetadata.title("  Café  ")).isEqualTo("Café");
    }

    @Test
    void blankOrMissingTitleIsRejected() {
        assertThatThrownBy(() -> UploadMetadata.title("   ")).isInstanceOf(UploadExceptions.InvalidMetadata.class);
        assertThatThrownBy(() -> UploadMetadata.title(null)).isInstanceOf(UploadExceptions.InvalidMetadata.class);
    }

    @Test
    void controlAndInvisibleFormatCharactersAreRejectedInATitle() {
        assertThatThrownBy(() -> UploadMetadata.title("bad\u0000title")).isInstanceOf(UploadExceptions.InvalidMetadata.class);
        assertThatThrownBy(() -> UploadMetadata.title("line\nbreak")).isInstanceOf(UploadExceptions.InvalidMetadata.class);
        // Right-to-left override and zero-width space: text that reads differently from how it is stored.
        assertThatThrownBy(() -> UploadMetadata.title("gnp.‮fdp")).isInstanceOf(UploadExceptions.InvalidMetadata.class);
        assertThatThrownBy(() -> UploadMetadata.title("zero​width")).isInstanceOf(UploadExceptions.InvalidMetadata.class);
    }

    @Test
    void aDescriptionMayKeepLineBreaksAndTabsButNotOtherControlCharacters() {
        assertThat(UploadMetadata.description("line one\nline two\tend")).isEqualTo("line one\nline two\tend");
        assertThatThrownBy(() -> UploadMetadata.description("bell\u0007")).isInstanceOf(UploadExceptions.InvalidMetadata.class);
    }

    @Test
    void anEmptyDescriptionBecomesNull() {
        assertThat(UploadMetadata.description(null)).isNull();
        assertThat(UploadMetadata.description("   ")).isNull();
    }

    @Test
    void lengthIsCheckedAfterNormalisation() {
        assertThat(UploadMetadata.title("a".repeat(150))).hasSize(150);
        assertThatThrownBy(() -> UploadMetadata.title("a".repeat(151))).isInstanceOf(UploadExceptions.InvalidMetadata.class);
        assertThatThrownBy(() -> UploadMetadata.description("a".repeat(2001))).isInstanceOf(UploadExceptions.InvalidMetadata.class);
    }
}

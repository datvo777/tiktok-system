package com.shortvideo.app.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.shortvideo.publication.domain.PublicationService;
import com.shortvideo.video.api.AssetLifecycleState;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * The publication module matches the video module's asset lifecycle states by name because it does
 * not depend on video. Renaming an enum value there would silently stop takedowns here, so the
 * names are pinned.
 */
class AssetLifecycleContractTest {

    @Test
    void publicationRemovalStatesAreRealAssetLifecycleStates() {
        Set<String> states = Arrays.stream(AssetLifecycleState.values()).map(Enum::name).collect(Collectors.toSet());

        assertThat(states).containsAll(PublicationService.REMOVAL_STATES);
    }
}

package com.shortvideo.video.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.shortvideo.video.api.AssetLifecycleState;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class VideoEntityAssetLifecycleTest {

    private static VideoEntity video() {
        return new VideoEntity(UUID.randomUUID(), UUID.randomUUID(), "title", "desc");
    }

    @Test
    void aReinstatementRestoresARetainedAsset() {
        VideoEntity video = video();
        video.retainAsRejected();

        assertThat(video.restoreAfterReinstatement(true)).isTrue();
        assertThat(video.getAssetLifecycleState()).isEqualTo(AssetLifecycleState.ACTIVE);
    }

    @Test
    void aReinstatementLeavesAnAdminQuarantineInPlace() {
        VideoEntity video = video();
        video.retainAsRejected();
        video.quarantine();

        assertThat(video.restoreAfterReinstatement(true)).isFalse();
        assertThat(video.getAssetLifecycleState()).isEqualTo(AssetLifecycleState.QUARANTINED);
    }

    @Test
    void anAdminRestoreLiftsAQuarantine() {
        VideoEntity video = video();
        video.quarantine();

        assertThat(video.releaseQuarantine(true)).isTrue();
        assertThat(video.getAssetLifecycleState()).isEqualTo(AssetLifecycleState.ACTIVE);
    }

    @Test
    void anAdminRestoreCannotLiftARejectionsRetention() {
        VideoEntity video = video();
        video.retainAsRejected();

        assertThat(video.releaseQuarantine(true)).isFalse();
        assertThat(video.getAssetLifecycleState()).isEqualTo(AssetLifecycleState.REJECTED_RETAINED);
    }

    @Test
    void neitherRestoreMovesAnAssetThatIsNotVerifiablyPresent() {
        VideoEntity retained = video();
        retained.retainAsRejected();
        VideoEntity quarantined = video();
        quarantined.quarantine();

        assertThat(retained.restoreAfterReinstatement(false)).isFalse();
        assertThat(quarantined.releaseQuarantine(false)).isFalse();
        assertThat(retained.getAssetLifecycleState()).isEqualTo(AssetLifecycleState.REJECTED_RETAINED);
        assertThat(quarantined.getAssetLifecycleState()).isEqualTo(AssetLifecycleState.QUARANTINED);
    }
}

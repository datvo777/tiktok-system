package com.shortvideo.app.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.shortvideo.social.api.EngagementReconciler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class EngagementReconciliationJobTest {

    private final EngagementReconciler reconciler = mock(EngagementReconciler.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final EngagementReconciliationJob job = new EngagementReconciliationJob(reconciler, meters);

    @Test
    void resumesWhereTheLastPageEndedAndStartsOverAfterTheEnd() {
        when(reconciler.reconcile(eq(null), anyInt(), eq(EngagementReconciliationJob.QUIET_FOR)))
                .thenReturn(new EngagementReconciler.Sweep("v500", 500, 0));
        job.reconcile();

        when(reconciler.reconcile(eq("v500"), anyInt(), eq(EngagementReconciliationJob.QUIET_FOR)))
                .thenReturn(new EngagementReconciler.Sweep(null, 40, 3));
        job.reconcile();

        // The sweep reached the end, so the next run begins again from the top.
        when(reconciler.reconcile(eq(null), anyInt(), eq(EngagementReconciliationJob.QUIET_FOR)))
                .thenReturn(new EngagementReconciler.Sweep(null, 0, 0));
        job.reconcile();
        verify(reconciler, org.mockito.Mockito.times(2)).reconcile(eq(null), anyInt(), eq(EngagementReconciliationJob.QUIET_FOR));
    }

    @Test
    void countsWhatItCheckedAndWhatItHadToCorrect() {
        when(reconciler.reconcile(eq(null), anyInt(), eq(EngagementReconciliationJob.QUIET_FOR)))
                .thenReturn(new EngagementReconciler.Sweep(null, 40, 3));

        job.reconcile();

        assertThat(meters.get("social.engagement.checked").counter().count()).isEqualTo(40);
        assertThat(meters.get("social.engagement.corrected").counter().count()).isEqualTo(3);
    }
}

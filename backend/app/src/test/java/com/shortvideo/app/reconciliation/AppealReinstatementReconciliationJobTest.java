package com.shortvideo.app.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.shortvideo.appeal.api.AppealDirectory;
import com.shortvideo.appeal.api.AppealDirectory.ApprovedAppealView;
import com.shortvideo.moderation.api.ModerationBackfill;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AppealReinstatementReconciliationJobTest {

    private static final Instant DECIDED = Instant.parse("2026-11-01T00:00:00Z");

    private final AppealDirectory appeals = mock(AppealDirectory.class);
    private final ModerationBackfill moderation = mock(ModerationBackfill.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private AppealReinstatementReconciliationJob job;

    @BeforeEach
    void setUp() {
        job = new AppealReinstatementReconciliationJob(appeals, moderation, meters);
        when(appeals.findApprovedPage(any(), any(), any(), anyInt()))
                .thenReturn(List.of(new ApprovedAppealView("v1", 3, DECIDED)));
    }

    @Test
    void anApprovedAppealModerationNeverAppliedIsReinstatedAtTheRejectionItNames() {
        when(moderation.reinstateFromAppeal("v1", 3)).thenReturn(true);

        job.reconcile();

        verify(moderation).reinstateFromAppeal("v1", 3);
        assertThat(meters.counter("appeal.reinstatement.repaired").count()).isEqualTo(1.0);
    }

    @Test
    void anApprovalThatAlreadyTookEffectOrWasOvertakenCountsAsNoRepair() {
        when(moderation.reinstateFromAppeal(anyString(), anyLong())).thenReturn(false);

        job.reconcile();

        assertThat(meters.counter("appeal.reinstatement.repaired").count()).isZero();
        assertThat(meters.counter("appeal.reinstatement.repair_failed").count()).isZero();
    }

    @Test
    void oneFailingRowDoesNotStopTheRest() {
        when(appeals.findApprovedPage(any(), any(), any(), anyInt()))
                .thenReturn(List.of(new ApprovedAppealView("v1", 3, DECIDED), new ApprovedAppealView("v2", 5, DECIDED)));
        when(moderation.reinstateFromAppeal("v1", 3)).thenThrow(new IllegalStateException("db down"));
        when(moderation.reinstateFromAppeal("v2", 5)).thenReturn(true);

        job.reconcile();

        assertThat(meters.counter("appeal.reinstatement.repair_failed").count()).isEqualTo(1.0);
        assertThat(meters.counter("appeal.reinstatement.repaired").count()).isEqualTo(1.0);
    }

    @Test
    void aFullPageMakesTheNextSweepContinueAfterItsLastRowAndAShortOneStartsOver() {
        List<ApprovedAppealView> full = IntStream.range(0, AppealReinstatementReconciliationJob.PAGE_SIZE)
                .mapToObj(i -> new ApprovedAppealView("v" + i, 1, DECIDED.plusSeconds(i)))
                .toList();
        when(appeals.findApprovedPage(any(), any(), any(), anyInt())).thenReturn(full, List.of());

        job.reconcile();
        job.reconcile();
        job.reconcile();

        var after = org.mockito.ArgumentCaptor.forClass(ApprovedAppealView.class);
        verify(appeals, times(3)).findApprovedPage(any(), any(), after.capture(), anyInt());
        assertThat(after.getAllValues()).containsExactly(null, full.get(full.size() - 1), null);
    }

    @Test
    void theSweepSkipsAppealsStillSettlingAndThoseTooOldToBeWorthScanning() {
        Instant before = Instant.now();

        job.reconcile();

        var from = org.mockito.ArgumentCaptor.forClass(Instant.class);
        var to = org.mockito.ArgumentCaptor.forClass(Instant.class);
        verify(appeals).findApprovedPage(from.capture(), to.capture(), eq(null), anyInt());
        assertThat(to.getValue()).isBetween(
                before.minus(AppealReinstatementReconciliationJob.SETTLE),
                Instant.now().minus(AppealReinstatementReconciliationJob.SETTLE));
        assertThat(from.getValue()).isBefore(to.getValue());
    }
}

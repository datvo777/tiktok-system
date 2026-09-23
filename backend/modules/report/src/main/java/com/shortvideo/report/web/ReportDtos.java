package com.shortvideo.report.web;

import com.shortvideo.report.api.ReportReason;
import com.shortvideo.report.api.ReportResolution;
import com.shortvideo.report.api.ReportState;
import com.shortvideo.report.api.ReportSubjectType;
import com.shortvideo.report.domain.ReportView;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

public final class ReportDtos {

    /**
     * {@code detail} is optional on purpose: a required free-text box is how a
     * report form ends up storing abuse aimed at the person being reported, and
     * the reason enum already carries what a moderator triages on.
     */
    public record CreateReportRequest(
            @NotNull ReportSubjectType subjectType,
            @NotNull ReportReason reason,
            @Size(max = 1000) String detail) {}

    public record ResolveReportRequest(
            @NotNull ReportResolution resolution, @Size(max = 1000) String note) {}

    /**
     * The reporter's own id is deliberately absent from what a moderator sees in
     * the list — the queue is triaged on the subject and the reason, and showing
     * who filed each report invites the decision to be made about the reporter.
     * It stays on the row for the audit trail.
     */
    public record ReportResponse(
            String reportId,
            ReportSubjectType subjectType,
            String subjectId,
            ReportReason reason,
            String detail,
            ReportState state,
            ReportResolution resolution,
            String resolutionNote,
            Instant createdAt,
            long openReportsForSubject) {

        public static ReportResponse from(ReportView view) {
            return new ReportResponse(
                    view.reportId(),
                    view.subjectType(),
                    view.subjectId(),
                    view.reason(),
                    view.detail(),
                    view.state(),
                    view.resolution(),
                    view.resolutionNote(),
                    view.createdAt(),
                    view.openReportsForSubject());
        }
    }

    public record ReportListResponse(List<ReportResponse> items) {
        public static ReportListResponse from(List<ReportView> views) {
            return new ReportListResponse(views.stream().map(ReportResponse::from).toList());
        }
    }

    private ReportDtos() {}
}

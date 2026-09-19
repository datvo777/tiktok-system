package com.shortvideo.report.domain;

import com.shortvideo.report.api.ReportReason;
import com.shortvideo.report.api.ReportResolution;
import com.shortvideo.report.api.ReportState;
import com.shortvideo.report.api.ReportSubjectType;
import java.time.Instant;

public record ReportView(
        String reportId,
        ReportSubjectType subjectType,
        String subjectId,
        String reporterId,
        ReportReason reason,
        String detail,
        ReportState state,
        ReportResolution resolution,
        String resolutionNote,
        Instant createdAt,
        /** How many open reports this subject has in total, including this one. */
        long openReportsForSubject) {

    static ReportView from(ReportEntity entity, long openReportsForSubject) {
        return new ReportView(
                entity.getReportId().toString(),
                entity.getSubjectType(),
                entity.getSubjectId().toString(),
                entity.getReporterId().toString(),
                entity.getReason(),
                entity.getDetail(),
                entity.getState(),
                entity.getResolution(),
                entity.getResolutionNote(),
                entity.getCreatedAt(),
                openReportsForSubject);
    }
}

package com.shortvideo.report.web;

import com.shortvideo.report.domain.ReportService;
import com.shortvideo.shared.security.AuthenticatedAccount;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Viewer-facing reporting. One endpoint, because a report is the same act
 * whatever it names — the subject type is part of the body rather than part of
 * the path so the client has one code path and one form.
 */
@RestController
@RequestMapping("/api/v1/reports")
@Tag(name = "Report")
public class ReportController {

    private final ReportService reportService;

    public ReportController(ReportService reportService) {
        this.reportService = reportService;
    }

    /**
     * 201 for a new report; also 201 for a repeat from the same person on the
     * same subject, which returns the report they already have. Reporting twice
     * is not an error worth showing someone.
     */
    @PostMapping("/{subjectId}")
    @Operation(summary = "Report a video, account or comment to moderation")
    public ResponseEntity<ReportDtos.ReportResponse> submit(
            @PathVariable UUID subjectId,
            @AuthenticationPrincipal AuthenticatedAccount caller,
            @Valid @RequestBody ReportDtos.CreateReportRequest request) {
        return ResponseEntity.status(201)
                .body(ReportDtos.ReportResponse.from(reportService.submit(
                        request.subjectType(),
                        subjectId.toString(),
                        caller.accountId(),
                        request.reason(),
                        request.detail())));
    }
}

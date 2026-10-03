package com.shortvideo.report.web;

import com.shortvideo.report.domain.ReportService;
import com.shortvideo.shared.security.AuthenticatedAccount;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The moderator side of reporting; ADMIN only, like the rest of /internal. */
@RestController
@RequestMapping("/internal/v1/reports")
@Tag(name = "Report (internal)")
@Validated
public class InternalReportController {

    private final ReportService reportService;

    public InternalReportController(ReportService reportService) {
        this.reportService = reportService;
    }

    @GetMapping("/open")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Open reports, oldest first, with a count per subject")
    public ReportDtos.ReportListResponse open(
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit) {
        return ReportDtos.ReportListResponse.from(reportService.openQueue(limit));
    }

    @PostMapping("/{reportId}/resolve")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Close a report with an outcome")
    public ReportDtos.ReportResponse resolve(
            @PathVariable UUID reportId,
            @AuthenticationPrincipal AuthenticatedAccount caller,
            @Valid @RequestBody ReportDtos.ResolveReportRequest request) {
        return ReportDtos.ReportResponse.from(reportService.resolve(
                reportId.toString(), request.resolution(), request.note(), caller.accountId()));
    }
}

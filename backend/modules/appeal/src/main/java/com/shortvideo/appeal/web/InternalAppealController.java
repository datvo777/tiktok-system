package com.shortvideo.appeal.web;

import com.shortvideo.appeal.domain.AppealService;
import com.shortvideo.appeal.domain.AppealView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin surface (brief section 18). {@code appealId} is the video's id — this
 * module keeps one appeal row per video, the same convention moderation and
 * publication use for their own aggregates.
 */
@RestController
@RequestMapping("/internal/v1/appeals")
@Tag(name = "Appeal (internal)")
public class InternalAppealController {

    private final AppealService appealService;

    public InternalAppealController(AppealService appealService) {
        this.appealService = appealService;
    }

    @GetMapping("/pending")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "List appeals awaiting a decision")
    public List<AppealDtos.AppealResponse> pending() {
        return appealService.listPending().stream().map(AppealDtos.AppealResponse::from).toList();
    }

    @PostMapping("/{appealId}/approve")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Approve an appeal; moderation reinstates the video if it is still rejected")
    public AppealDtos.AppealResponse approve(
            @PathVariable UUID appealId, @RequestBody(required = false) AppealDtos.DecisionRequest request) {
        AppealView view = appealService.approve(appealId.toString(), request == null ? null : request.reason());
        return AppealDtos.AppealResponse.from(view);
    }

    @PostMapping("/{appealId}/deny")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Deny an appeal")
    public AppealDtos.AppealResponse deny(
            @PathVariable UUID appealId, @RequestBody(required = false) AppealDtos.DecisionRequest request) {
        AppealView view = appealService.deny(appealId.toString(), request == null ? null : request.reason());
        return AppealDtos.AppealResponse.from(view);
    }
}

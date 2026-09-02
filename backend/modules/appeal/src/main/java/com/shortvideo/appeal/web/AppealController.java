package com.shortvideo.appeal.web;

import java.util.UUID;
import com.shortvideo.appeal.domain.AppealService;
import com.shortvideo.shared.security.AuthenticatedAccount;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Creator-facing appeal submission (brief section 12, Appeal API). */
@RestController
@RequestMapping("/api/v1/videos")
@Tag(name = "Appeal")
public class AppealController {

    private final AppealService appealService;

    public AppealController(AppealService appealService) {
        this.appealService = appealService;
    }

    @PostMapping("/{videoId}/appeals")
    @Operation(summary = "Owner appeals a rejected video")
    public AppealDtos.AppealResponse submit(
            @PathVariable UUID videoId,
            @Valid @RequestBody AppealDtos.SubmitRequest request,
            @AuthenticationPrincipal AuthenticatedAccount caller) {
        return AppealDtos.AppealResponse.from(appealService.submit(videoId.toString(), caller.accountId(), request.reason()));
    }
}

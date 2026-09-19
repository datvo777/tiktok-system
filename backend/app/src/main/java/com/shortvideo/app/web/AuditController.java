package com.shortvideo.app.web;

import com.shortvideo.shared.audit.AdminActionEntry;
import com.shortvideo.shared.audit.AdminActionReader;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read surface for the admin audit log.
 *
 * <p>Lives in the composition root rather than in {@code shared/audit} because
 * the shared modules here are libraries — none of them expose HTTP. It is not
 * owned by any one domain module either: the log deliberately spans moderation,
 * video lifecycle, appeals and accounts, so putting it inside any of them would
 * make that module the arbitrary owner of the other three's history.
 */
@RestController
@RequestMapping("/internal/v1/audit")
@Tag(name = "Audit (internal)")
@Validated
public class AuditController {

    private final AdminActionReader reader;

    public AuditController(AdminActionReader reader) {
        this.reader = reader;
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Admin actions against one video or account, newest first")
    public List<AdminActionEntry> forTarget(
            @RequestParam @NotBlank String targetType,
            @RequestParam @NotBlank String targetId,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit) {
        return reader.forTarget(targetType, targetId, limit);
    }

    @GetMapping("/by-actor")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Everything one admin has done, newest first")
    public List<AdminActionEntry> byActor(
            @RequestParam @NotBlank String actorAccountId,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit) {
        return reader.byActor(actorAccountId, limit);
    }
}

package com.shortvideo.upload.web;

import com.shortvideo.shared.security.AuthenticatedAccount;
import com.shortvideo.upload.domain.UploadService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/uploads")
@Tag(name = "Upload")
public class UploadController {

    private final UploadService uploadService;

    public UploadController(UploadService uploadService) {
        this.uploadService = uploadService;
    }

    @PostMapping
    @Operation(summary = "Create an upload session; also creates the video draft (brief section 7.1)")
    public ResponseEntity<UploadDtos.CreateUploadResponse> create(@AuthenticationPrincipal AuthenticatedAccount caller) {
        var created = uploadService.createSession(caller.accountId());
        return ResponseEntity.created(URI.create("/api/v1/uploads/" + created.uploadId()))
                .body(UploadDtos.CreateUploadResponse.from(created));
    }

    @PostMapping("/{uploadId}/complete")
    @Operation(summary = "Mark the direct-to-MinIO upload complete; idempotent and owner-checked")
    public UploadDtos.UploadResponse complete(
            @PathVariable UUID uploadId,
            @AuthenticationPrincipal AuthenticatedAccount caller,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return UploadDtos.UploadResponse.from(uploadService.complete(uploadId.toString(), caller.accountId(), idempotencyKey));
    }

    @GetMapping("/{uploadId}")
    @Operation(summary = "Read an upload session; owner-only")
    public UploadDtos.UploadResponse get(
            @PathVariable UUID uploadId, @AuthenticationPrincipal AuthenticatedAccount caller) {
        return UploadDtos.UploadResponse.from(uploadService.find(uploadId.toString(), caller.accountId()));
    }
}

package com.shortvideo.notification.web;

import com.shortvideo.notification.domain.NotificationService;
import com.shortvideo.shared.security.AuthenticatedAccount;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Basic in-app notifications (brief section 20, Milestone 7). */
@RestController
@RequestMapping("/api/v1/notifications")
@Tag(name = "Notifications")
@Validated
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @GetMapping
    @Operation(summary = "One page of the caller's notifications, newest first")
    public NotificationDtos.NotificationListResponse list(
            @AuthenticationPrincipal AuthenticatedAccount caller,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "0") @Min(0) @Max(100) int limit) {
        return NotificationDtos.NotificationListResponse.from(
                notificationService.listForRecipient(caller.accountId(), cursor, limit));
    }

    /**
     * One statement instead of one request per notification, and it clears the
     * caller's whole history rather than only the rows the client had fetched.
     */
    @PostMapping("/read-all")
    @Operation(summary = "Mark all of the caller's notifications as read")
    public NotificationDtos.MarkAllReadResponse markAllRead(@AuthenticationPrincipal AuthenticatedAccount caller) {
        return new NotificationDtos.MarkAllReadResponse(notificationService.markAllRead(caller.accountId()));
    }

    @PostMapping("/{notificationId}/read")
    @Operation(summary = "Mark one of the caller's own notifications as read")
    public ResponseEntity<Void> markRead(
            @PathVariable UUID notificationId, @AuthenticationPrincipal AuthenticatedAccount caller) {
        notificationService.markRead(notificationId.toString(), caller.accountId());
        return ResponseEntity.noContent().build();
    }
}

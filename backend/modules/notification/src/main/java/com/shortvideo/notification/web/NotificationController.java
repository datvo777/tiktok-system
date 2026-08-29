package com.shortvideo.notification.web;

import com.shortvideo.notification.domain.NotificationService;
import com.shortvideo.shared.security.AuthenticatedAccount;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Basic in-app notifications (brief section 20, Milestone 7). */
@RestController
@RequestMapping("/api/v1/notifications")
@Tag(name = "Notifications")
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @GetMapping
    @Operation(summary = "List the caller's own notifications, newest first")
    public List<NotificationDtos.NotificationResponse> list(@AuthenticationPrincipal AuthenticatedAccount caller) {
        return notificationService.listForRecipient(caller.accountId()).stream()
                .map(NotificationDtos.NotificationResponse::from)
                .toList();
    }

    @PostMapping("/{notificationId}/read")
    @Operation(summary = "Mark one of the caller's own notifications as read")
    public ResponseEntity<Void> markRead(
            @PathVariable String notificationId, @AuthenticationPrincipal AuthenticatedAccount caller) {
        notificationService.markRead(notificationId, caller.accountId());
        return ResponseEntity.noContent().build();
    }
}

package com.shortvideo.publication.web;

import com.shortvideo.publication.domain.PublicationService;
import com.shortvideo.shared.security.AuthenticatedAccount;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/videos")
@Tag(name = "Publication")
public class PublicationController {

    private final PublicationService publicationService;

    public PublicationController(PublicationService publicationService) {
        this.publicationService = publicationService;
    }

    @PostMapping("/{videoId}/publish")
    @Operation(summary = "Owner requests publication; the coordinator evaluates prerequisites (brief section 8)")
    public PublicationDtos.PublicationResponse publish(
            @PathVariable String videoId, @AuthenticationPrincipal AuthenticatedAccount caller) {
        return PublicationDtos.PublicationResponse.from(
                publicationService.requestPublish(videoId, caller.accountId()));
    }
}

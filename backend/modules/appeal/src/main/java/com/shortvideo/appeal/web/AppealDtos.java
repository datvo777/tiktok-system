package com.shortvideo.appeal.web;

import com.shortvideo.appeal.domain.AppealView;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class AppealDtos {

    public record SubmitRequest(@NotBlank @Size(max = 1000) String reason) {}

    public record DecisionRequest(@Size(max = 1000) String reason) {}

    public record AppealResponse(String videoId, String state, String reason, String decisionReason) {
        public static AppealResponse from(AppealView view) {
            return new AppealResponse(view.videoId(), view.state().name(), view.reason(), view.decisionReason());
        }
    }

    private AppealDtos() {}
}

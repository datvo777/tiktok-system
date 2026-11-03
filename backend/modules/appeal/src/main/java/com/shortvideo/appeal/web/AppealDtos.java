package com.shortvideo.appeal.web;

import com.shortvideo.appeal.domain.AppealView;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class AppealDtos {

    public record SubmitRequest(@NotBlank @Size(max = 1000) String reason) {}

    /**
     * The reason is shown to the creator and copied into the audit trail, whose column holds 500, so
     * that is the limit; {@code expectedVersion} is the {@code version} the admin was shown.
     */
    public record DecisionRequest(@NotBlank @Size(max = 500) String reason, @NotNull Long expectedVersion) {}

    public record AppealResponse(String videoId, String state, String reason, String decisionReason, long version) {
        public static AppealResponse from(AppealView view) {
            return new AppealResponse(
                    view.videoId(), view.state().name(), view.reason(), view.decisionReason(), view.version());
        }
    }

    private AppealDtos() {}
}

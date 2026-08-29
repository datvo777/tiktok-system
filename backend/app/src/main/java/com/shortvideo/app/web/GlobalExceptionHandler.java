package com.shortvideo.app.web;

import com.shortvideo.account.domain.AccountExceptions;
import com.shortvideo.appeal.domain.AppealExceptions;
import com.shortvideo.moderation.domain.ModerationExceptions;
import com.shortvideo.notification.domain.NotificationExceptions;
import com.shortvideo.playback.InvalidMediaPathException;
import com.shortvideo.playback.MediaAuthorizationException;
import com.shortvideo.publication.domain.PublicationExceptions;
import com.shortvideo.social.domain.SocialExceptions;
import com.shortvideo.upload.domain.UploadExceptions;
import com.shortvideo.video.domain.VideoExceptions;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(AccountExceptions.EmailAlreadyRegistered.class)
    public ProblemDetail conflict(AccountExceptions.EmailAlreadyRegistered e) {
        return problem(HttpStatus.CONFLICT, "Email already registered", e.getMessage());
    }

    /**
     * Both bad credentials and a non-active account answer 401 with the same
     * wording, so the response does not reveal which addresses are registered or
     * which accounts are suspended.
     */
    @ExceptionHandler({AccountExceptions.InvalidCredentials.class, AccountExceptions.AccountNotActive.class})
    public ProblemDetail unauthorized(RuntimeException e) {
        return problem(HttpStatus.UNAUTHORIZED, "Unauthorized", "Invalid email or password");
    }

    @ExceptionHandler(AccountExceptions.AccountNotFound.class)
    public ProblemDetail notFound(AccountExceptions.AccountNotFound e) {
        return problem(HttpStatus.NOT_FOUND, "Not found", e.getMessage());
    }

    @ExceptionHandler(InvalidMediaPathException.class)
    public ProblemDetail badMediaPath(InvalidMediaPathException e) {
        // No media bytes, and no echo of the rejected path.
        return problem(HttpStatus.BAD_REQUEST, "Invalid media path", "The requested asset path is not valid");
    }

    @ExceptionHandler(MediaAuthorizationException.Unauthorized.class)
    public ProblemDetail mediaUnauthorized(MediaAuthorizationException.Unauthorized e) {
        return problem(HttpStatus.UNAUTHORIZED, "Unauthorized", "Valid playback credentials are required");
    }

    @ExceptionHandler(MediaAuthorizationException.Forbidden.class)
    public ProblemDetail mediaForbidden(MediaAuthorizationException.Forbidden e) {
        return problem(HttpStatus.FORBIDDEN, "Forbidden", "Playback is not authorized for this request");
    }

    @ExceptionHandler(MediaAuthorizationException.Unavailable.class)
    public ProblemDetail mediaUnavailable(MediaAuthorizationException.Unavailable e) {
        log.warn("Media authorization state unavailable", e);
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "Unavailable", "Unable to establish playback safety");
    }

    @ExceptionHandler(MediaAuthorizationException.ObjectMissing.class)
    public ProblemDetail mediaObjectMissing(MediaAuthorizationException.ObjectMissing e) {
        return problem(HttpStatus.NOT_FOUND, "Not found", "The requested media object was not found");
    }

    @ExceptionHandler({VideoExceptions.VideoNotFound.class, UploadExceptions.UploadNotFound.class})
    public ProblemDetail notFoundGeneric(RuntimeException e) {
        return problem(HttpStatus.NOT_FOUND, "Not found", e.getMessage());
    }

    @ExceptionHandler({
        VideoExceptions.NotVideoOwner.class,
        VideoExceptions.VideoNotReady.class,
        UploadExceptions.NotUploadOwner.class
    })
    public ProblemDetail forbiddenGeneric(RuntimeException e) {
        return problem(HttpStatus.FORBIDDEN, "Forbidden", e.getMessage());
    }

    @ExceptionHandler(UploadExceptions.UploadExpired.class)
    public ProblemDetail uploadExpired(UploadExceptions.UploadExpired e) {
        return problem(HttpStatus.GONE, "Upload expired", e.getMessage());
    }

    @ExceptionHandler({UploadExceptions.UploadObjectMissing.class, UploadExceptions.UploadSizeOutOfRange.class})
    public ProblemDetail uploadInvalid(RuntimeException e) {
        return problem(HttpStatus.CONFLICT, "Upload invalid", e.getMessage());
    }

    @ExceptionHandler({
        ModerationExceptions.ModerationRecordNotFound.class,
        PublicationExceptions.PublicationNotFound.class,
        AppealExceptions.AppealNotFound.class
    })
    public ProblemDetail moderationOrPublicationNotFound(RuntimeException e) {
        return problem(HttpStatus.NOT_FOUND, "Not found", e.getMessage());
    }

    @ExceptionHandler({PublicationExceptions.NotVideoOwner.class, AppealExceptions.NotVideoOwner.class})
    public ProblemDetail notPublicationOwner(RuntimeException e) {
        return problem(HttpStatus.FORBIDDEN, "Forbidden", e.getMessage());
    }

    @ExceptionHandler({AppealExceptions.NotEligibleForAppeal.class, AppealExceptions.AppealNotPending.class})
    public ProblemDetail appealInvalid(RuntimeException e) {
        return problem(HttpStatus.CONFLICT, "Appeal invalid", e.getMessage());
    }

    @ExceptionHandler(NotificationExceptions.NotificationNotFound.class)
    public ProblemDetail notificationNotFound(NotificationExceptions.NotificationNotFound e) {
        return problem(HttpStatus.NOT_FOUND, "Not found", e.getMessage());
    }

    @ExceptionHandler(SocialExceptions.VideoNotEligible.class)
    public ProblemDetail videoNotEligible(SocialExceptions.VideoNotEligible e) {
        return problem(HttpStatus.NOT_FOUND, "Not found", e.getMessage());
    }

    @ExceptionHandler(SocialExceptions.CreatorNotFound.class)
    public ProblemDetail creatorNotFound(SocialExceptions.CreatorNotFound e) {
        return problem(HttpStatus.NOT_FOUND, "Not found", e.getMessage());
    }

    @ExceptionHandler(SocialExceptions.CannotFollowSelf.class)
    public ProblemDetail cannotFollowSelf(SocialExceptions.CannotFollowSelf e) {
        return problem(HttpStatus.CONFLICT, "Invalid follow", e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail validation(MethodArgumentNotValidException e) {
        ProblemDetail detail = problem(HttpStatus.BAD_REQUEST, "Validation failed", "One or more fields are invalid");
        Map<String, String> errors = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors()
                .forEach(fieldError -> errors.putIfAbsent(fieldError.getField(), fieldError.getDefaultMessage()));
        detail.setProperty("errors", errors);
        return detail;
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail unexpected(Exception e) {
        log.error("Unhandled exception", e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error", "Unexpected error");
    }

    private ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatus(status);
        problem.setTitle(title);
        problem.setDetail(detail);
        return problem;
    }
}

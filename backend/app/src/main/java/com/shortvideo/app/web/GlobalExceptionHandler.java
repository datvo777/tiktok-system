package com.shortvideo.app.web;

import com.shortvideo.account.domain.AccountExceptions;
import com.shortvideo.appeal.domain.AppealExceptions;
import com.shortvideo.moderation.domain.ModerationExceptions;
import com.shortvideo.notification.domain.NotificationExceptions;
import com.shortvideo.playback.InvalidMediaPathException;
import com.shortvideo.playback.MediaAuthorizationException;
import com.shortvideo.playback.MediaRangeNotSatisfiableException;
import com.shortvideo.playback.MediaStreamsExhaustedException;
import com.shortvideo.publication.domain.PublicationExceptions;
import com.shortvideo.social.domain.SocialExceptions;
import com.shortvideo.upload.domain.UploadExceptions;
import com.shortvideo.video.domain.VideoExceptions;
import jakarta.validation.ConstraintViolationException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Extends {@link ResponseEntityExceptionHandler} so Spring MVC's own exception
 * family keeps its correct status codes. A bare {@code @RestControllerAdvice} with
 * an {@code @ExceptionHandler(Exception.class)} catch-all swallows them: malformed
 * JSON ({@code HttpMessageNotReadableException}), a non-numeric path variable or
 * query parameter ({@code MethodArgumentTypeMismatchException}), an unsupported
 * method or media type — all of which are 4xx — were being reported as 500.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Authorization failures must reach Spring Security's {@code accessDeniedHandler},
     * which is what actually renders the 403 configured in {@code SecurityConfig}.
     *
     * <p>This advice runs inside {@code DispatcherServlet}, which is <em>before</em>
     * {@code ExceptionTranslationFilter} in the chain. Without this handler the
     * catch-all below claimed every {@code @PreAuthorize} denial first and answered
     * 500 — access was still correctly refused, but the status was wrong, the
     * denial was logged as an unhandled server error, and the admin client's
     * "403 means no ADMIN role" branch was unreachable. Rethrowing lets the
     * exception continue out to the filter that knows how to translate it.
     */
    @ExceptionHandler({AccessDeniedException.class, AuthorizationDeniedException.class})
    public void authorizationDenied(RuntimeException e) {
        throw e;
    }

    /**
     * Raised by {@code @Validated} on method parameters — e.g. the {@code @Min}/
     * {@code @Max} bounds on the moderation queue's page size. Distinct from
     * {@link MethodArgumentNotValidException}, which covers {@code @Valid} request
     * bodies, and previously fell through to the catch-all as a 500.
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ProblemDetail constraintViolation(ConstraintViolationException e) {
        ProblemDetail detail = problem(HttpStatus.BAD_REQUEST, "Validation failed", "One or more parameters are invalid");
        Map<String, String> errors = new LinkedHashMap<>();
        e.getConstraintViolations()
                .forEach(violation -> errors.putIfAbsent(
                        violation.getPropertyPath().toString(), violation.getMessage()));
        detail.setProperty("errors", errors);
        return detail;
    }

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

    /** RFC 9110 §15.5.17: 416 must carry the object's true size so a client can retry. */
    @ExceptionHandler(MediaRangeNotSatisfiableException.class)
    public ResponseEntity<ProblemDetail> mediaRangeNotSatisfiable(MediaRangeNotSatisfiableException e) {
        return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                .header(HttpHeaders.CONTENT_RANGE, "bytes */" + e.totalSize())
                .body(problem(
                        HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE,
                        "Range not satisfiable",
                        "The requested byte range lies outside this object"));
    }

    /** Backpressure, not failure: tell the client to come back rather than truncating a 200. */
    @ExceptionHandler(MediaStreamsExhaustedException.class)
    public ResponseEntity<ProblemDetail> mediaStreamsExhausted(MediaStreamsExhaustedException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "1")
                .body(problem(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "Busy",
                        "The media gateway is at capacity; retry shortly"));
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

    @ExceptionHandler(ModerationExceptions.InvalidCursor.class)
    public ProblemDetail invalidCursor(ModerationExceptions.InvalidCursor e) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid cursor", e.getMessage());
    }

    /**
     * An override rather than a second {@code @ExceptionHandler}: the base class
     * already maps this type, and declaring it again here is an ambiguous mapping
     * that fails at context startup. Overriding keeps the per-field {@code errors}
     * map the clients rely on.
     */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException e,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        ProblemDetail detail = problem(HttpStatus.BAD_REQUEST, "Validation failed", "One or more fields are invalid");
        Map<String, String> errors = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors()
                .forEach(fieldError -> errors.putIfAbsent(fieldError.getField(), fieldError.getDefaultMessage()));
        detail.setProperty("errors", errors);
        return ResponseEntity.badRequest().body(detail);
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

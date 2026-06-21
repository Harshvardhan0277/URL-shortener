package com.example.urlshortener.exception;

import com.example.urlshortener.dto.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

/**
 * ==============================================================================
 * GlobalExceptionHandler — a single, centralized place that converts every
 * exception thrown ANYWHERE in our controllers/services into a consistent
 * JSON ErrorResponse with the correct HTTP status code.
 * ==============================================================================
 * @RestControllerAdvice
 * ------------------------------------------------------------------------------
 * This is a combination of @ControllerAdvice (a Spring mechanism that lets a
 * class apply "advice" — i.e. cross-cutting behavior — across ALL
 * @RestController classes in the app, without each controller needing its
 * own try/catch blocks) and @ResponseBody (so whatever each handler method
 * returns is serialized straight to JSON, just like a normal @RestController
 * method).
 *
 * WHY CENTRALIZE ERROR HANDLING HERE INSTEAD OF try/catch IN EVERY
 * CONTROLLER METHOD?
 *   - DRY: we define the ErrorResponse JSON shape and timestamp logic
 *     exactly ONCE.
 *   - Controllers stay focused purely on the "happy path" — they simply
 *     call the service layer and let exceptions propagate; they don't need
 *     to know HTTP status codes for failure cases AT ALL.
 *   - Guarantees every endpoint, including ones we add in the future, gets
 *     consistent error formatting automatically (since this advice applies
 *     globally) instead of relying on every developer remembering to add a
 *     try/catch.
 * ==============================================================================
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * Handles UrlNotFoundException (thrown by UrlService/AnalyticsService
     * when a short code doesn't exist) by mapping it to HTTP 404.
     *
     * @ExceptionHandler(UrlNotFoundException.class) tells Spring's
     * DispatcherServlet: "whenever a controller method (or anything it
     * calls) throws this exact exception type, route execution here instead
     * of letting it bubble up as an unhandled 500 error."
     */
    @ExceptionHandler(UrlNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleUrlNotFound(UrlNotFoundException ex, HttpServletRequest request) {
        ErrorResponse body = ErrorResponse.of(
                HttpStatus.NOT_FOUND.value(),
                HttpStatus.NOT_FOUND.getReasonPhrase(),
                ex.getMessage(),
                request.getRequestURI()
        );
        // ResponseEntity lets us control the exact HTTP status line AND body
        // together. HttpStatus.NOT_FOUND = 404.
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
    }

    /**
     * Handles InvalidUrlException by mapping it to HTTP 400 Bad Request —
     * the client sent a request that violates a business rule.
     */
    @ExceptionHandler(InvalidUrlException.class)
    public ResponseEntity<ErrorResponse> handleInvalidUrl(InvalidUrlException ex, HttpServletRequest request) {
        ErrorResponse body = ErrorResponse.of(
                HttpStatus.BAD_REQUEST.value(),
                HttpStatus.BAD_REQUEST.getReasonPhrase(),
                ex.getMessage(),
                request.getRequestURI()
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    /**
     * Handles MethodArgumentNotValidException — the exception Spring itself
     * throws automatically when a @Valid-annotated @RequestBody (like our
     * CreateUrlRequest) fails its Jakarta Bean Validation constraints
     * (@NotBlank, @Pattern, etc — see CreateUrlRequest).
     *
     * We extract every individual field error (there can be several at
     * once, e.g. multiple invalid fields in one request) into a flat list
     * of human-readable strings for the `details` field of ErrorResponse,
     * so API consumers see exactly which field(s) were wrong and why.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        List<String> details = ex.getBindingResult().getFieldErrors().stream()
                .map(fieldError -> fieldError.getField() + ": " + fieldError.getDefaultMessage())
                .toList();

        ErrorResponse body = new ErrorResponse(
                java.time.LocalDateTime.now(),
                HttpStatus.BAD_REQUEST.value(),
                HttpStatus.BAD_REQUEST.getReasonPhrase(),
                "Validation failed for the request body",
                request.getRequestURI(),
                details
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    /**
     * Handles Bucket4j's "no tokens left" scenario. NOTE: in this project,
     * the actual 429 response is produced directly inside RateLimitFilter
     * (a servlet Filter runs BEFORE Spring's DispatcherServlet/exception
     * handling machinery even gets invoked, so a @RestControllerAdvice
     * CANNOT intercept rejections that happen at the filter level). This
     * handler is included to show how a Bucket4j-related exception WOULD be
     * handled if rate limiting were instead implemented as a Spring
     * interceptor/aspect that throws an exception rather than a raw
     * Filter — documented further in RateLimitFilter's comments.
     */
    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ErrorResponse> handleRateLimitExceeded(RateLimitExceededException ex, HttpServletRequest request) {
        ErrorResponse body = ErrorResponse.of(
                429,
                "Too Many Requests",
                ex.getMessage(),
                request.getRequestURI()
        );
        return ResponseEntity.status(429).body(body);
    }

    /**
     * Catch-all fallback for any exception type we did NOT explicitly
     * anticipate above. Without this, an unexpected bug (e.g. a
     * NullPointerException from a coding mistake) would leak Spring Boot's
     * default whitelabel error page or a raw stack trace to API clients —
     * neither professional nor safe (stack traces can leak internal
     * implementation details). We log the full exception server-side (for
     * us to debug) but return only a generic, safe message to the client.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGenericException(Exception ex, HttpServletRequest request) {
        // In a real production system this would go through a proper
        // logging framework (SLF4J/Logback) at ERROR level, possibly with
        // an alert/metric — kept as a comment here to stay focused on the
        // educational HTTP-mapping concern.
        // log.error("Unhandled exception processing request: " + request.getRequestURI(), ex);

        ErrorResponse body = ErrorResponse.of(
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                HttpStatus.INTERNAL_SERVER_ERROR.getReasonPhrase(),
                "An unexpected error occurred. Please try again later.",
                request.getRequestURI()
        );
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }
}

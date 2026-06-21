package com.example.urlshortener.exception;

/**
 * ==============================================================================
 * RateLimitExceededException — represents "this client has exceeded their
 * allotted request rate."
 * ==============================================================================
 * Defined for completeness and documentation purposes alongside
 * GlobalExceptionHandler.handleRateLimitExceeded(). In THIS project's
 * actual implementation, rate limiting is enforced inside RateLimitFilter
 * (a raw servlet Filter that runs before Spring MVC's exception-handling
 * machinery is even reached), so the 429 response is written directly
 * there rather than by throwing this exception. We keep this class because
 * it documents the alternative (and in many teams, preferred) design of
 * implementing rate limiting as a Spring HandlerInterceptor that throws
 * this exception instead, letting GlobalExceptionHandler produce the 429 —
 * see the comment block in RateLimitFilter for the full trade-off
 * discussion.
 * ==============================================================================
 */
public class RateLimitExceededException extends RuntimeException {
    public RateLimitExceededException(String message) {
        super(message);
    }
}

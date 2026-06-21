package com.example.urlshortener.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * ==============================================================================
 * ErrorResponse — the single, consistent JSON shape returned for EVERY error
 * in this API (404s, 400 validation failures, 429 rate-limit, 500s).
 * ==============================================================================
 * WHY THIS MATTERS: without a consistent error contract, every endpoint
 * might fail differently, forcing API consumers to write bespoke error
 * handling per-endpoint. By funneling every exception through
 * GlobalExceptionHandler into THIS one shape, any client (a frontend app, a
 * Postman test, another backend service) can handle errors uniformly:
 * "read `status` and `message`, optionally inspect `details`."
 * ==============================================================================
 */
public record ErrorResponse(
        LocalDateTime timestamp,
        int status,             // HTTP status code, e.g. 404
        String error,           // short machine-friendly reason phrase, e.g. "Not Found"
        String message,         // human-readable explanation
        String path,            // the request path that caused the error, for easier debugging
        List<String> details    // optional: e.g. field-level validation error messages
) {
    /** Convenience factory for the common case of no extra field-level details. */
    public static ErrorResponse of(int status, String error, String message, String path) {
        return new ErrorResponse(LocalDateTime.now(), status, error, message, path, null);
    }
}

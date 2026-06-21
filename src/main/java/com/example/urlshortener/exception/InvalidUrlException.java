package com.example.urlshortener.exception;

/**
 * ==============================================================================
 * InvalidUrlException — thrown for domain-level URL validation failures that
 * are too nuanced to express via a simple @Pattern annotation on the DTO
 * (e.g. business rules like "we don't allow shortening URLs that point back
 * at our own domain, to prevent infinite redirect loops").
 * ==============================================================================
 * Kept separate from UrlNotFoundException because they map to DIFFERENT
 * HTTP status codes (400 Bad Request here, vs 404 Not Found there) — having
 * one exception type per distinct failure MEANING (not just one giant
 * generic exception) is what lets GlobalExceptionHandler route each to the
 * correct status code precisely.
 * ==============================================================================
 */
public class InvalidUrlException extends RuntimeException {

    public InvalidUrlException(String message) {
        super(message);
    }
}

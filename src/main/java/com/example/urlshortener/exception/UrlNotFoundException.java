package com.example.urlshortener.exception;

/**
 * ==============================================================================
 * UrlNotFoundException — thrown when a requested short code does not exist
 * in the database.
 * ==============================================================================
 * A custom, UNCHECKED exception (extends RuntimeException, not Exception)
 * so callers in the service layer don't need to clutter every method
 * signature with `throws UrlNotFoundException` or wrap every call in
 * try/catch — Java lets unchecked exceptions propagate up the call stack
 * automatically until something chooses to catch them.
 *
 * In this project, that "something" is GlobalExceptionHandler, which
 * catches this specific exception type and converts it into an HTTP 404
 * response — keeping HTTP-status concerns entirely out of the service
 * layer (the service layer just expresses "this domain error happened";
 * the web layer decides what HTTP status that maps to).
 * ==============================================================================
 */
public class UrlNotFoundException extends RuntimeException {

    public UrlNotFoundException(String shortCode) {
        super("No URL mapping found for short code: " + shortCode);
    }
}

package com.example.urlshortener.service;

import com.example.urlshortener.dto.CreateUrlResponse;

/**
 * ==============================================================================
 * CreateUrlResult — an internal wrapper returned by UrlService.createShortUrl(),
 * pairing the response payload with a flag indicating whether a NEW row was
 * created or an EXISTING short URL for this same original URL was found and
 * reused.
 * ==============================================================================
 * WHY THIS LIVES IN THE service PACKAGE, NOT dto:
 *   This is NOT part of the public JSON API contract — the client only ever
 *   sees the `response` field's contents serialized as the response body.
 *   `alreadyExisted` exists purely so UrlController can decide between
 *   returning HTTP 201 Created (a new resource was made) versus HTTP 200 OK
 *   (an existing resource was found and returned) — a REST best practice for
 *   idempotent "create-or-return-existing" endpoints. Keeping this
 *   distinction OUT of the dto package keeps our public API DTOs focused
 *   purely on "what shape does the client see," exactly as described in
 *   CreateUrlResponse's own Javadoc.
 *
 * This is a Java `record` for the same reasons as our DTOs: it's an
 * immutable, two-field data carrier with no behavior of its own.
 * ==============================================================================
 */
public record CreateUrlResult(CreateUrlResponse response, boolean alreadyExisted) {
}

package com.example.urlshortener.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * ==============================================================================
 * CreateUrlRequest — DTO (Data Transfer Object) representing the JSON body a
 * client sends to POST /api/urls to create a new shortened URL.
 * ==============================================================================
 * WHY DTOs EXIST AT ALL (instead of accepting/returning @Entity objects
 * directly in controllers):
 *   1. SEPARATION OF CONCERNS: our @Entity (UrlMapping) describes database
 *      structure. Our API contract (what JSON shape clients send/receive)
 *      is a DIFFERENT concern that may evolve independently — e.g. we might
 *      rename a database column without breaking the public API, or vice
 *      versa.
 *   2. SECURITY: if a controller accepted a UrlMapping entity directly from
 *      request JSON, a malicious client could set fields like `clickCount`
 *      or `id` directly in the request body ("mass assignment" /
 *      over-posting vulnerability). A request DTO only exposes the exact
 *      fields we WANT the client to control.
 *   3. VALIDATION: we attach Jakarta Bean Validation annotations here so
 *      Spring rejects malformed requests automatically (HTTP 400) before
 *      any of our business logic runs.
 *
 * This is a Java `record` — a compact, immutable data carrier (introduced in
 * Java 16+) that automatically generates a canonical constructor, getters
 * (named after the field, e.g. `originalUrl()`), equals(), hashCode(), and
 * toString(). Perfect fit for DTOs, which are just "bags of data" with no
 * behavior.
 * ==============================================================================
 */
public record CreateUrlRequest(

        @NotBlank(message = "originalUrl must not be blank")
        // @NotBlank rejects null, empty string, and whitespace-only strings.

        @Pattern(
                regexp = "^(https?)://[\\w.-]+(?:\\.[\\w.-]+)+[\\w\\-._~:/?#\\[\\]@!$&'()*+,;=.]*$",
                message = "originalUrl must be a valid http:// or https:// URL"
        )
        // A pragmatic regex check that the URL starts with http(s):// and has
        // a plausible host. We deliberately keep this permissive (URLs are
        // notoriously hard to validate perfectly with regex) — it's a first
        // line of defense, not a full RFC 3986 parser.
        String originalUrl

) {
}

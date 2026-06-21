package com.example.urlshortener.dto;

import java.time.LocalDateTime;

/**
 * ==============================================================================
 * CreateUrlResponse — DTO returned to the client after a short URL is
 * successfully created (HTTP 201 from POST /api/urls).
 * ==============================================================================
 * Like CreateUrlRequest, this is a `record` — immutable and purely a data
 * carrier. Returning this instead of the raw UrlMapping entity means:
 *   - We control EXACTLY what's exposed (we deliberately do NOT leak
 *     clickEvents or internal database row shape).
 *   - We can include a COMPUTED field (`shortUrl`, the full clickable link)
 *     that doesn't exist as a column at all — it's built by the service
 *     layer by combining the configured app.base-url with the shortCode.
 * ==============================================================================
 */
public record CreateUrlResponse(
        String shortCode,      // e.g. "b7F3a"
        String shortUrl,       // e.g. "http://localhost:8080/b7F3a" — ready to share/click
        String originalUrl,    // echoed back so the client can confirm what was shortened
        LocalDateTime createdAt
) {
}

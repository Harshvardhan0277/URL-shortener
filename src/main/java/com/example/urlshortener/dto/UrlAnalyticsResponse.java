package com.example.urlshortener.dto;

import java.time.LocalDateTime;

/**
 * ==============================================================================
 * UrlAnalyticsResponse — DTO returned by GET /api/analytics/{shortCode}.
 * ==============================================================================
 * Encapsulates everything requirement #6 asks for about a SINGLE short URL:
 * total clicks and last-accessed time, alongside identifying context
 * (shortCode/originalUrl) so the response is self-describing without the
 * client needing to already know what URL they asked about.
 * ==============================================================================
 */
public record UrlAnalyticsResponse(
        String shortCode,
        String originalUrl,
        long totalClicks,
        LocalDateTime createdAt,
        LocalDateTime lastAccessedAt   // null if the link has never been clicked
) {
}

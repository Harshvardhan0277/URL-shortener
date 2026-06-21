package com.example.urlshortener.dto;

/**
 * ==============================================================================
 * TopUrlResponse — DTO representing one entry in the "top URLs by clicks"
 * leaderboard returned by GET /api/analytics/top.
 * ==============================================================================
 * Deliberately a SLIM projection (just 3 fields) rather than reusing
 * UrlAnalyticsResponse — a top-N leaderboard endpoint is typically called
 * for a dashboard widget and doesn't need timestamps, keeping the JSON
 * payload small when returning, say, the top 50 links at once.
 * ==============================================================================
 */
public record TopUrlResponse(
        String shortCode,
        String originalUrl,
        long clickCount
) {
}

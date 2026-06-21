package com.example.urlshortener.service;

import com.example.urlshortener.dto.TopUrlResponse;
import com.example.urlshortener.dto.UrlAnalyticsResponse;

import java.util.List;

/**
 * ==============================================================================
 * AnalyticsService — the Service-layer contract for all read-only reporting
 * operations (requirement #6 in the spec: total clicks, last accessed,
 * top URLs).
 * ==============================================================================
 * Kept as its OWN service (separate from UrlService) following the SINGLE
 * RESPONSIBILITY PRINCIPLE: UrlService owns "create + resolve" (the
 * write-heavy, latency-critical redirect path), while AnalyticsService owns
 * "report on existing data" (read-heavy, less latency-sensitive). This
 * separation also means if we later wanted to, say, point analytics queries
 * at a read-replica database for scaling, we'd only need to reconfigure
 * AnalyticsServiceImpl, leaving the core redirect path untouched.
 * ==============================================================================
 */
public interface AnalyticsService {

    /**
     * Returns total clicks, created/last-accessed timestamps, and the
     * original URL for a single short code. Throws UrlNotFoundException
     * (→ HTTP 404 via GlobalExceptionHandler) if the code doesn't exist.
     */
    UrlAnalyticsResponse getAnalytics(String shortCode);

    /**
     * Returns the top `limit` short URLs ordered by click count,
     * descending — powers a "most popular links" dashboard/leaderboard.
     */
    List<TopUrlResponse> getTopUrls(int limit);
}

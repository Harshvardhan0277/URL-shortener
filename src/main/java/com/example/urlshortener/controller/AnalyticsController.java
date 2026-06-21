package com.example.urlshortener.controller;

import com.example.urlshortener.dto.TopUrlResponse;
import com.example.urlshortener.dto.UrlAnalyticsResponse;
import com.example.urlshortener.service.AnalyticsService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * ==============================================================================
 * AnalyticsController — exposes the analytics APIs required by spec item #6:
 * total clicks, last accessed, and top URLs by clicks.
 * ==============================================================================
 * Kept as its own controller (rather than bundling these endpoints into
 * UrlController) for the same single-responsibility reasoning as
 * AnalyticsService: URL creation/redirection is one concern, reporting on
 * existing data is a distinct concern, and most teams version/scale/secure
 * "read APIs for dashboards" differently from "the core product redirect
 * path" in a real system.
 * ==============================================================================
 */
@RestController
@RequestMapping("/api/analytics")
// Class-level @RequestMapping prefixes every method below with /api/analytics,
// so e.g. @GetMapping("/{shortCode}") below actually maps to
// GET /api/analytics/{shortCode}. This avoids repeating "/api/analytics" in
// every single method's mapping annotation.
public class AnalyticsController {

    private final AnalyticsService analyticsService;

    public AnalyticsController(AnalyticsService analyticsService) {
        this.analyticsService = analyticsService;
    }

    /**
     * GET /api/analytics/{shortCode}
     * ------------------------------------------------------------------------
     * Returns total clicks, last-accessed timestamp, and created-at
     * timestamp for one specific short URL. Throws UrlNotFoundException
     * (-> HTTP 404 via GlobalExceptionHandler) if the short code is
     * unknown.
     */
    @GetMapping("/{shortCode}")
    public ResponseEntity<UrlAnalyticsResponse> getAnalytics(@PathVariable String shortCode) {
        UrlAnalyticsResponse response = analyticsService.getAnalytics(shortCode);
        return ResponseEntity.ok(response);
        // ResponseEntity.ok(...) is shorthand for status(200).body(...) —
        // 200 OK is the correct status for a successful read/GET.
    }

    /**
     * GET /api/analytics/top?limit=10
     * ------------------------------------------------------------------------
     * Returns the top N most-clicked short URLs.
     *
     * @RequestParam(defaultValue = "10") int limit binds the optional
     * ?limit= query parameter; if the client omits it entirely, Spring
     * supplies "10" and converts it to an int automatically. Using a
     * query parameter (rather than a path variable) is the idiomatic REST
     * choice here since `limit` is an optional MODIFIER of the request,
     * not part of the resource's identity/path.
     *
     * We cap the maximum allowed value server-side (rather than trusting
     * the client) to prevent an accidental or malicious `?limit=1000000`
     * from forcing a huge, expensive result set / response payload.
     */
    @GetMapping("/top")
    public ResponseEntity<List<TopUrlResponse>> getTopUrls(@RequestParam(defaultValue = "10") int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), 100);
        // Math.max(limit, 1)   -> never allow 0 or negative values
        // Math.min(..., 100)   -> never allow more than 100 results per call
        List<TopUrlResponse> response = analyticsService.getTopUrls(safeLimit);
        return ResponseEntity.ok(response);
    }
}

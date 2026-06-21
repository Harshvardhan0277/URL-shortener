package com.example.urlshortener.service;

import com.example.urlshortener.dto.CreateUrlRequest;

/**
 * ==============================================================================
 * UrlService — the Service-layer CONTRACT for URL shortening and resolving.
 * ==============================================================================
 * WHY AN INTERFACE HERE (with a single UrlServiceImpl implementation),
 * RATHER THAN JUST WRITING UrlServiceImpl DIRECTLY AND HAVING CONTROLLERS
 * DEPEND ON IT?
 *   1. PROGRAM TO AN ABSTRACTION: UrlController depends on this interface,
 *      not the concrete class. This means we could swap in a different
 *      implementation (e.g. UrlServiceCachedImpl that adds a Redis cache in
 *      front of the database) without changing a single line of
 *      UrlController.
 *   2. TESTABILITY: in controller unit tests (see UrlControllerTest), we
 *      can create a Mockito mock of THIS interface and inject it, fully
 *      isolating the controller's HTTP-handling logic from real business
 *      logic / database calls.
 *   3. DOCUMENTATION: the interface, read on its own, is a clean summary of
 *      "what can the URL service DO" without any implementation noise.
 *
 * This is the SERVICE LAYER of our architecture — it owns BUSINESS LOGIC
 * (the Base62 generation strategy, click-counting rules, transaction
 * boundaries) and sits between the Controller layer (HTTP concerns only)
 * and the Repository layer (persistence concerns only).
 * ==============================================================================
 */
public interface UrlService {

    /**
     * Creates a new shortened URL for the given original URL — OR, if this
     * exact URL has already been shortened before, returns the EXISTING
     * mapping instead of creating a duplicate row. The returned
     * CreateUrlResult tells the caller (UrlController) which case
     * happened, so it can return the correct HTTP status (201 Created for
     * a brand-new short URL, 200 OK for an existing one).
     */
    CreateUrlResult createShortUrl(CreateUrlRequest request);

    /**
     * Resolves a short code back to its original URL AND, as a side
     * effect, records a click (increments the counter, updates
     * last-accessed time, and inserts a ClickEvent row) — this is the
     * core operation behind the GET /{shortCode} redirect endpoint.
     *
     * Takes ipAddress/userAgent so the resulting ClickEvent audit row can
     * capture who/what made the request (extracted from the HttpServletRequest
     * in the controller, since the service layer should never depend on
     * the Servlet API directly — see UrlController for that extraction).
     *
     * @return the original URL the client should be redirected to.
     */
    String resolveAndRecordClick(String shortCode, String ipAddress, String userAgent);
}

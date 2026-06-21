package com.example.urlshortener.controller;

import com.example.urlshortener.dto.CreateUrlRequest;
import com.example.urlshortener.dto.CreateUrlResponse;
import com.example.urlshortener.service.CreateUrlResult;
import com.example.urlshortener.service.UrlService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;

/**
 * ==============================================================================
 * UrlController — the Controller (Web) layer for creating short URLs and
 * resolving/redirecting them.
 * ==============================================================================
 * This is the ONLY layer in our architecture that is allowed to know about
 * HTTP-specific concepts: status codes, headers, request/response objects.
 * It deliberately contains NO business logic itself — every method body is
 * just "extract what we need from the HTTP request" -> "delegate to the
 * service layer" -> "translate the service's result into an HTTP response."
 * This thin-controller / fat-service split is what keeps business logic
 * reusable and unit-testable independent of the web framework.
 *
 * @RestController
 * ------------------------------------------------------------------------------
 * Shorthand for @Controller + @ResponseBody combined: marks this class as a
 * Spring MVC controller (so its methods can be mapped to HTTP routes) AND
 * tells Spring that every method's return value should be serialized
 * directly into the HTTP response body (as JSON, via Jackson) rather than
 * being interpreted as the name of a server-side view template to render.
 *
 * @RequestMapping("/")
 * ------------------------------------------------------------------------------
 * A class-level prefix applied to every method's mapping below. We use the
 * root path "/" here (rather than e.g. "/api") specifically because the
 * redirect endpoint needs to live at the bare root (GET /{shortCode}, e.g.
 * "http://localhost:8080/b7F3a") to produce the shortest, most shareable
 * URLs — that's the entire point of a URL shortener. The creation endpoint
 * is still explicitly mapped under /api/urls below for a clean separation
 * between "API for managing links" and "the public redirect surface."
 * ==============================================================================
 */
@RestController
public class UrlController {

    private final UrlService urlService;

    // Constructor injection — see UrlServiceImpl's Javadoc for the full
    // rationale (testability, immutability via `final`, explicit
    // dependencies).
    public UrlController(UrlService urlService) {
        this.urlService = urlService;
    }

    /**
     * POST /api/urls
     * ------------------------------------------------------------------------
     * Creates a new shortened URL — OR, if this exact URL was already
     * shortened before, returns the EXISTING short code instead of
     * minting a duplicate (see UrlServiceImpl.createShortUrl for the
     * full duplicate-detection logic).
     *
     * @RequestBody @Valid CreateUrlRequest request
     *   - @RequestBody tells Spring to deserialize the incoming HTTP
     *     request's JSON body into a CreateUrlRequest object (via Jackson).
     *   - @Valid tells Spring to run Jakarta Bean Validation (the
     *     @NotBlank/@Pattern annotations declared on CreateUrlRequest's
     *     field) against that deserialized object BEFORE this method body
     *     even runs. If validation fails, Spring throws
     *     MethodArgumentNotValidException automatically — which never
     *     reaches our method body at all; it's caught by
     *     GlobalExceptionHandler.handleValidation() and turned into an
     *     HTTP 400 response.
     *
     * STATUS CODE CHOICE — 201 vs 200:
     *   HTTP 201 Created is the semantically correct status for "a brand
     *   new resource was made." But if we instead found and returned an
     *   ALREADY-EXISTING short URL for this same original URL, no new
     *   resource was created — so we return 200 OK instead, matching the
     *   REST convention for idempotent "create-or-return-existing"
     *   endpoints. UrlService communicates which case happened via the
     *   `alreadyExisted` flag on CreateUrlResult.
     */
    @PostMapping("/api/urls")
    public ResponseEntity<CreateUrlResponse> createShortUrl(@Valid @RequestBody CreateUrlRequest request) {
        CreateUrlResult result = urlService.createShortUrl(request);
        CreateUrlResponse response = result.response();

        HttpStatus status = result.alreadyExisted() ? HttpStatus.OK : HttpStatus.CREATED;

        // Building the Location header is a REST best-practice whenever a
        // response identifies a specific resource: it tells the client
        // exactly where that resource can be found/accessed (here: the
        // short URL itself, which IS the resource's "address" in a very
        // literal sense for this domain). We include it for both the
        // newly-created and the already-existing case.
        URI location = URI.create(response.shortUrl());

        return ResponseEntity
                .status(status)
                .header(HttpHeaders.LOCATION, location.toString())
                .body(response);
    }

    /**
     * GET /{shortCode}
     * ------------------------------------------------------------------------
     * The actual redirect endpoint — this is what a user's browser hits
     * when they click a shortened link.
     *
     * Mapped at the ROOT level (no /api prefix) specifically so generated
     * short URLs are as short and clean as possible: "host/b7F3a" instead
     * of "host/api/urls/b7F3a".
     *
     * @PathVariable("shortCode") String shortCode binds the {shortCode}
     * segment of the URL path directly into this method parameter.
     *
     * HTTP 302 FOUND vs other redirect codes — WHY 302 SPECIFICALLY (as
     * required by spec):
     *   - 301 Moved Permanently tells browsers/search engines "cache this
     *     redirect forever and never ask the server again", which would
     *     mean our click-counting/analytics logic in the service layer
     *     would stop running after a browser's first visit (it would
     *     redirect locally from cache without ever hitting our server
     *     again) — catastrophic for an analytics-focused URL shortener.
     *   - 307/308 preserve the original HTTP method and body strictly
     *     (relevant for non-GET requests); not needed here since this is
     *     always a simple GET.
     *   - 302 Found means "temporarily redirect; ask me again next time" —
     *     exactly the semantics we want: every single click MUST hit our
     *     server again so we can record it.
     */
    @GetMapping("/{shortCode}")
    public ResponseEntity<Void> redirect(@PathVariable String shortCode, HttpServletRequest request) {
        // Extract client metadata for the ClickEvent audit row. This is THE
        // ONE place in the whole codebase allowed to touch the raw
        // HttpServletRequest, precisely because it's HTTP-specific
        // information (IP, User-Agent header) — the service layer receives
        // it as plain Strings and has no idea (and doesn't need to know)
        // it came from a servlet request at all.
        String ipAddress = resolveClientIp(request);
        String userAgent = request.getHeader("User-Agent");

        String originalUrl = urlService.resolveAndRecordClick(shortCode, ipAddress, userAgent);

        return ResponseEntity
                .status(HttpStatus.FOUND)  // HTTP 302
                .header(HttpHeaders.LOCATION, originalUrl)
                .build();
        // .build() (not .body(...)) because a redirect response has no
        // meaningful body — the browser reads the Location header and
        // immediately issues a new GET request to that URL itself.
    }

    /**
     * Same IP-resolution logic as RateLimitFilter (see that class's
     * detailed Javadoc for the X-Forwarded-For trust caveat). Duplicated
     * here intentionally rather than sharing a utility, since these two
     * call sites belong to conceptually different layers (a Filter vs a
     * Controller) and keeping them independent avoids accidentally
     * coupling unrelated parts of the request-handling pipeline together
     * for what is, in practice, a single three-line helper.
     */
    private String resolveClientIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}

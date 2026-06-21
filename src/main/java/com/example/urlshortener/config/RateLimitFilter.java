package com.example.urlshortener.config;

import com.example.urlshortener.dto.ErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * ==============================================================================
 * RateLimitFilter — a Servlet Filter that enforces the Bucket4j token-bucket
 * rate limit on every incoming HTTP request, BEFORE it reaches Spring MVC.
 * ==============================================================================
 * WHY A SERVLET FILTER, RATHER THAN E.G. A SPRING HandlerInterceptor OR
 * CHECKING THE BUCKET MANUALLY INSIDE EACH CONTROLLER METHOD?
 *   - A Filter runs at the SERVLET CONTAINER level (Tomcat), BEFORE
 *     Spring's DispatcherServlet does any routing/controller-resolution
 *     work at all. This means a rejected request never even reaches Spring
 *     MVC's machinery — the absolute cheapest possible place to reject
 *     abusive traffic, protecting the rest of the application (including
 *     things like @Valid validation, JSON deserialization, etc.) from ever
 *     running for requests we're going to refuse anyway.
 *   - It applies UNIFORMLY to every endpoint automatically (we don't have
 *     to remember to add a check to each new controller method we write in
 *     the future).
 *   (A HandlerInterceptor would also work and run "almost" as early — the
 *   trade-off is mostly philosophical/architectural here; Filters are the
 *   more classic choice for concerns, like rate limiting and authentication,
 *   that are not really part of "handling this specific endpoint's
 *   business logic" but rather a property of the HTTP layer itself.)
 *
 * extends OncePerRequestFilter
 * ------------------------------------------------------------------------------
 * A convenient Spring base class that guarantees its filtering logic runs
 * EXACTLY ONCE per request, even in environments (like certain async/
 * forward/error-dispatch scenarios) where a request might otherwise pass
 * through the filter chain more than once. We just implement
 * doFilterInternal() and Spring handles that bookkeeping for us.
 *
 * @Component
 * ------------------------------------------------------------------------------
 * Registers this filter as a Spring bean. Spring Boot auto-detects ANY bean
 * implementing the Servlet Filter interface (which OncePerRequestFilter
 * implements) and automatically registers it into the embedded Tomcat's
 * filter chain — no extra FilterRegistrationBean configuration required for
 * the simple "apply to all URLs" case we want here.
 * ==============================================================================
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private final RateLimitConfig rateLimitConfig;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    // ObjectMapper is Jackson's JSON (de)serializer. We need our OWN
    // instance here (rather than @Autowiring Spring's shared one) because
    // a raw Filter runs outside Spring MVC's normal dependency-injected
    // request-handling pipeline; constructing a lightweight ObjectMapper
    // directly is simple and avoids any ordering complications with bean
    // initialization. findAndRegisterModules() ensures Java 8 time types
    // (LocalDateTime, used in ErrorResponse) serialize correctly.

    public RateLimitFilter(RateLimitConfig rateLimitConfig) {
        this.rateLimitConfig = rateLimitConfig;
    }

    /**
     * doFilterInternal
     * ------------------------------------------------------------------------
     * The method Spring calls for every single incoming HTTP request.
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                     HttpServletResponse response,
                                     FilterChain filterChain) throws ServletException, IOException {

        String clientIp = resolveClientIp(request);
        Bucket bucket = rateLimitConfig.resolveBucket(clientIp);

        // tryConsumeAndReturnRemaining(1) ATOMICALLY attempts to remove 1
        // token from the bucket and tells us the outcome via a
        // ConsumptionProbe — "atomically" matters because this method
        // itself is thread-safe even though many requests for the SAME IP
        // could arrive concurrently.
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);

        if (probe.isConsumed()) {
            // Token successfully spent — request is allowed through.
            // We also surface the remaining quota as a response header,
            // a common REST API convention (similar to GitHub's API) that
            // lets well-behaved clients self-throttle BEFORE they actually
            // get rejected.
            response.addHeader("X-RateLimit-Remaining", String.valueOf(probe.getRemainingTokens()));
            filterChain.doFilter(request, response);
            // ^ Passes control to the NEXT filter in the chain, and
            // eventually to Spring's DispatcherServlet, which routes the
            // request to the matching @RestController method. Without
            // calling this, the request would simply hang/dead-end here.
        } else {
            // No tokens left: reject with HTTP 429 Too Many Requests
            // BEFORE Spring MVC, our controllers, or our service/business
            // logic ever execute for this request.
            long nanosUntilNextToken = probe.getNanosToWaitForRefill();
            long secondsUntilNextToken = nanosUntilNextToken / 1_000_000_000;

            response.setStatus(429); // HttpServletResponse has no named constant for 429 pre-Java EE updates; 429 is the standard "Too Many Requests" status code.
            response.setContentType("application/json");
            response.addHeader("Retry-After", String.valueOf(secondsUntilNextToken));
            // Retry-After is the standard HTTP header telling well-behaved
            // clients exactly how many seconds to wait before retrying,
            // computed directly from Bucket4j's refill calculation.

            ErrorResponse body = ErrorResponse.of(
                    429,
                    "Too Many Requests",
                    "Rate limit exceeded. Please retry after " + secondsUntilNextToken + " second(s).",
                    request.getRequestURI()
            );
            response.getWriter().write(objectMapper.writeValueAsString(body));
            // We write the JSON body ourselves (rather than throwing an
            // exception for GlobalExceptionHandler to catch) precisely
            // BECAUSE this code runs in a raw Servlet Filter, outside
            // Spring MVC's @RestControllerAdvice machinery entirely — by
            // the time an exception thrown here could even be considered,
            // Spring's exception-resolving infrastructure isn't in the
            // call stack for this request yet.
        }
    }

    /**
     * resolveClientIp
     * ------------------------------------------------------------------------
     * Determines the "real" client IP to key the rate-limit bucket by.
     *
     * We check the X-Forwarded-For header FIRST because in any real
     * deployment, this Spring Boot app typically sits behind a reverse
     * proxy or load balancer (nginx, an AWS ALB, etc.), which means
     * request.getRemoteAddr() would return the PROXY's IP for every single
     * client — making rate limiting useless (it would lump every real
     * user into one shared bucket keyed by the proxy's address). Proxies
     * conventionally add the ORIGINAL client IP into the X-Forwarded-For
     * header (format: "client, proxy1, proxy2, ..."), so we take the
     * first entry in that list.
     *
     * CAVEAT (documented honestly): X-Forwarded-For is a plain HTTP header
     * that a malicious client could forge directly if there is NO trusted
     * proxy actually validating/overwriting it. A hardened production
     * deployment would configure the reverse proxy to strip any
     * client-supplied X-Forwarded-For value and set its own, and/or use
     * Spring's ForwardedHeaderFilter with an explicit trusted-proxy
     * allowlist. For this project's scope we implement the common,
     * pragmatic pattern and call this trade-off out explicitly rather than
     * silently glossing over it.
     */
    private String resolveClientIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}

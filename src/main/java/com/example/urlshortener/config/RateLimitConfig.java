package com.example.urlshortener.config;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * ==============================================================================
 * RateLimitConfig — owns the logic for creating and looking up a Bucket4j
 * "token bucket" for each client, keyed by IP address.
 * ==============================================================================
 * HOW THE TOKEN BUCKET ALGORITHM WORKS (Bucket4j's core model):
 *   Imagine a literal bucket that can hold up to `capacity` tokens. Every
 *   incoming request must "spend" 1 token to proceed. The bucket starts
 *   full. Tokens are automatically refilled at a steady rate (here:
 *   `refillTokens` tokens added every `refillDurationSeconds`). If a
 *   client makes requests faster than the refill rate, their bucket
 *   eventually empties, and further requests are REJECTED (HTTP 429) until
 *   enough time passes for tokens to regenerate.
 *
 *   This allows brief BURSTS up to the bucket's capacity (e.g. a user
 *   legitimately clicking 5 links in 2 seconds), while still strictly
 *   capping the AVERAGE sustained rate over time — a better user
 *   experience than a naive "max N requests per fixed 60-second window"
 *   counter, which can unfairly reject a burst right at a window boundary.
 *
 * WHY KEY THE BUCKET BY IP ADDRESS?
 *   This is a public API with no authentication/API-key concept (out of
 *   scope per the requirements), so IP address is the most practical
 *   available signal to distinguish "different clients" for fairness.
 *   (In a system with API keys or logged-in users, we'd key by that
 *   identity instead — see the interview Q&A in the README for this
 *   trade-off discussion, including IP spoofing/NAT caveats.)
 * ==============================================================================
 */
@Component
public class RateLimitConfig {

    // @Value injects these from application.yml's `app.rate-limit.*`
    // properties, so operators can tune rate limits via config/environment
    // variables without recompiling code.
    @Value("${app.rate-limit.capacity}")
    private int capacity;

    @Value("${app.rate-limit.refill-tokens}")
    private int refillTokens;

    @Value("${app.rate-limit.refill-duration-seconds}")
    private int refillDurationSeconds;

    /**
     * In-memory registry of one Bucket per client IP. ConcurrentHashMap is
     * used (not a plain HashMap) because this map is read/written from
     * MULTIPLE simultaneous HTTP request threads (Tomcat handles each
     * request on its own thread) — ConcurrentHashMap provides thread-safe
     * get/put operations without us needing to manually synchronize,
     * avoiding both data corruption AND a global lock bottleneck.
     *
     * TRADE-OFF / SCALING NOTE: this map lives in THIS JVM's memory only.
     * If the application were horizontally scaled to multiple instances
     * behind a load balancer, each instance would track rate limits
     * independently — a client could get up to N times the intended quota
     * by being routed to N different instances. A production
     * multi-instance deployment would instead back Bucket4j with a shared
     * store (e.g. Redis, via the bucket4j-redis module) so all instances
     * consult the SAME bucket state. Documented further in the README's
     * "Scaling Considerations" section — kept as in-memory here to match
     * the requirement of using Bucket4j without introducing an additional
     * infrastructure dependency (Redis) for this project's scope.
     */
    private final ConcurrentHashMap<String, Bucket> bucketsByIp = new ConcurrentHashMap<>();

    /**
     * resolveBucket
     * ------------------------------------------------------------------------
     * Returns the existing Bucket for this IP if one exists, or atomically
     * creates and stores a brand-new one if this is the IP's first request.
     *
     * ConcurrentHashMap.computeIfAbsent(key, mappingFunction) is used
     * instead of a manual "check-then-create" (get(), then if null put())
     * because the manual version has a race condition: two threads could
     * BOTH see `null` for the same brand-new IP and BOTH create a fresh
     * bucket, with one silently overwriting the other (and effectively
     * resetting that client's quota — exactly the bug we're trying to
     * avoid by rate-limiting in the first place). computeIfAbsent
     * guarantees the mapping function runs AT MOST ONCE per key even under
     * concurrent calls.
     */
    public Bucket resolveBucket(String ipAddress) {
        return bucketsByIp.computeIfAbsent(ipAddress, ip -> newBucket());
    }

    /**
     * newBucket
     * ------------------------------------------------------------------------
     * Builds a fresh Bucket4j Bucket configured with a single Bandwidth
     * limit derived from our application.yml settings.
     *
     * Bandwidth.classic(capacity, refill) means:
     *   - the bucket can hold up to `capacity` tokens at once (the maximum
     *     burst size), and
     *   - Refill.intervally(refillTokens, duration) means exactly
     *     `refillTokens` tokens are added back ALL AT ONCE every
     *     `duration` (as opposed to Refill.greedy, which adds tokens
     *     continuously/smoothly — "intervally" is simpler to reason about
     *     for this demo: e.g. "20 tokens, refilled every 60 seconds" reads
     *     naturally as "20 requests per minute").
     */
    private Bucket newBucket() {
        Bandwidth limit = Bandwidth.classic(
                capacity,
                io.github.bucket4j.Refill.intervally(refillTokens, Duration.ofSeconds(refillDurationSeconds))
        );
        return Bucket.builder().addLimit(limit).build();
    }
}

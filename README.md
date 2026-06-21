java -jar target/url-shortener.jar

# URL Shortener — Production-Style Spring Boot 3 / Java 21 / MySQL Project

A fully-layered, production-style URL shortener built with Java 21, Spring Boot 3,
Spring Data JPA, MySQL, and Bucket4j rate limiting. Every source file contains
extremely detailed comments explaining each class, method, annotation, and the
exact SQL Hibernate generates — this README is the companion guide tying it
all together: how to run it, test it, deploy it, and explain it in an
interview.

---

## 1. Project Structure

```
url-shortener/
├── pom.xml                          # Maven build file (Java 21, Spring Boot 3, Bucket4j)
├── Dockerfile                       # Multi-stage build -> small runtime image
├── docker-compose.yml               # App + MySQL, one command to run both
├── .gitignore
├── README.md                        # ← you are here
│
├── src/main/resources/
│   ├── application.yml              # DB connection, JPA, rate-limit, logging config
│   └── schema.sql                   # Reference production DDL with explicit indexes
│
├── src/main/java/com/example/urlshortener/
│   ├── UrlShortenerApplication.java # @SpringBootApplication entry point
│   │
│   ├── entity/                      # ── ENTITY LAYER (JPA / database mapping) ──
│   │   ├── UrlMapping.java          #   id, originalUrl, shortCode, clickCount, timestamps
│   │   └── ClickEvent.java          #   per-click audit row: timestamp, IP, user-agent
│   │
│   ├── dto/                         # ── DTO LAYER (API contract) ──
│   │   ├── CreateUrlRequest.java    #   POST /api/urls request body (validated)
│   │   ├── CreateUrlResponse.java   #   POST /api/urls response body
│   │   ├── UrlAnalyticsResponse.java#   GET /api/analytics/{shortCode} response
│   │   ├── TopUrlResponse.java      #   one entry in GET /api/analytics/top
│   │   └── ErrorResponse.java       #   consistent error JSON shape for ALL errors
│   │
│   ├── repository/                  # ── REPOSITORY LAYER (Spring Data JPA) ──
│   │   ├── UrlMappingRepository.java#   findByShortCode, top-N query, pessimistic lock
│   │   └── ClickEventRepository.java#   per-link click history queries
│   │
│   ├── util/
│   │   └── Base62Encoder.java       # encode(id)/decode(code) — collision-safe short codes
│   │
│   ├── service/                     # ── SERVICE LAYER (business logic) ──
│   │   ├── UrlService.java          #   interface: create + resolve/redirect
│   │   ├── UrlServiceImpl.java      #   insert-then-update Base62 strategy, locking
│   │   ├── AnalyticsService.java    #   interface: reporting
│   │   └── AnalyticsServiceImpl.java#   total clicks, last accessed, top URLs
│   │
│   ├── config/                      # ── RATE LIMITING (Bucket4j) ──
│   │   ├── RateLimitConfig.java     #   per-IP token-bucket creation/lookup
│   │   └── RateLimitFilter.java     #   Servlet Filter enforcing the limit, HTTP 429
│   │
│   ├── exception/                   # ── ERROR HANDLING ──
│   │   ├── UrlNotFoundException.java
│   │   ├── InvalidUrlException.java
│   │   ├── RateLimitExceededException.java
│   │   └── GlobalExceptionHandler.java  # @RestControllerAdvice -> consistent error JSON
│   │
│   └── controller/                  # ── CONTROLLER LAYER (HTTP only) ──
│       ├── UrlController.java       #   POST /api/urls, GET /{shortCode} (302 redirect)
│       └── AnalyticsController.java #   GET /api/analytics/{shortCode}, /top
│
└── src/test/
    ├── java/.../service/UrlServiceImplTest.java     # pure Mockito unit test
    ├── java/.../controller/UrlControllerTest.java   # @WebMvcTest slice test
    └── resources/application-test.yml               # H2 config for future integration tests
```

**Request flow for a redirect:** `RateLimitFilter` (servlet filter, runs first) →
`UrlController.redirect()` → `UrlServiceImpl.resolveAndRecordClick()` →
`UrlMappingRepository` (SELECT by short_code, then SELECT...FOR UPDATE) +
`ClickEventRepository` (INSERT) → HTTP 302 back to the browser.

---

## 2. How Short Codes Are Generated (the core design)

**Step 0 — duplicate check (idempotent create):** before creating anything,
we check whether this exact URL has already been shortened, by looking up a
SHA-256 hash of it (the raw URL is too long to index directly in MySQL). If
a match is found, we return the **existing** short code — `POST`-ing the
same URL twice always returns the same `shortCode`/`shortUrl`, with HTTP
`200 OK` the second time instead of `201 Created`. This also keeps a
single link's click analytics from being silently fragmented across
multiple short codes.

If the URL is genuinely new:
1. A new `UrlMapping` row is inserted with `short_code = NULL`. MySQL's
   `AUTO_INCREMENT` assigns a guaranteed-unique `id` (e.g. `125`).
2. We Base62-encode that `id` → `"21"` (62-symbol alphabet: `0-9a-zA-Z`).
3. We `UPDATE` the same row to set `short_code = '21'`.
4. Both steps run inside one `@Transactional` method — if step 3 ever failed,
   step 1 rolls back too.

Because Base62 encoding is a pure mathematical bijection, two different ids
can **never** produce the same code — collision-safety comes from the
database's own `AUTO_INCREMENT` guarantee, with no "generate, check if
taken, retry" loop anywhere in the code.

---

## 3. Running Locally

### Option A — Docker Compose (recommended, zero local setup)
```bash
docker compose up --build
```
This builds the app image, starts MySQL, waits for MySQL's healthcheck to
pass, then starts the app on `http://localhost:8080`.

### Option B — Run against a local MySQL install
```bash
# 1. Make sure MySQL is running locally and create the database (or let
#    the JDBC URL's createDatabaseIfNotExist=true handle it).
# 2. Build and run:
mvn clean package
java -jar target/url-shortener.jar

# Or, with custom credentials:
DB_USERNAME=myuser DB_PASSWORD=mypass java -jar target/url-shortener.jar
```

### Running tests
```bash
mvn test
```

---

## 4. API Reference, curl, and Postman Examples

### 4.1 Create a short URL
```bash
curl -i -X POST http://localhost:8080/api/urls \
  -H "Content-Type: application/json" \
  -d '{"originalUrl": "https://www.example.com/some/very/long/path?query=1"}'
```
**Response — `201 Created`:**
```json
{
  "shortCode": "21",
  "shortUrl": "http://localhost:8080/21",
  "originalUrl": "https://www.example.com/some/very/long/path?query=1",
  "createdAt": "2026-06-20T10:15:30.123"
}
```
**Postman:** Method `POST`, URL `http://localhost:8080/api/urls`, Body → raw → JSON,
same payload as above.

**Idempotency check** — POST the exact same `originalUrl` again:
```bash
curl -i -X POST http://localhost:8080/api/urls \
  -H "Content-Type: application/json" \
  -d '{"originalUrl": "https://www.example.com/some/very/long/path?query=1"}'
```
This returns `200 OK` (not `201 Created`) with the **same** `shortCode` as
before — no duplicate row is created.

### 4.2 Use the short URL (redirect)
```bash
curl -i http://localhost:8080/21
# HTTP/1.1 302 Found
# Location: https://www.example.com/some/very/long/path?query=1
```
Note `-i` (not `-L`) so curl **shows** the redirect response instead of
silently following it — useful for verifying the 302 status and `Location`
header directly. In a browser, simply visiting `http://localhost:8080/21`
redirects automatically.

**Postman:** GET `http://localhost:8080/21` with "Automatically follow
redirects" turned OFF in Settings, to inspect the raw 302 response.

### 4.3 Get analytics for one short URL
```bash
curl -s http://localhost:8080/api/analytics/21 | jq
```
**Response — `200 OK`:**
```json
{
  "shortCode": "21",
  "originalUrl": "https://www.example.com/some/very/long/path?query=1",
  "totalClicks": 3,
  "createdAt": "2026-06-20T10:15:30.123",
  "lastAccessedAt": "2026-06-20T10:20:11.987"
}
```

### 4.4 Get top URLs by click count
```bash
curl -s "http://localhost:8080/api/analytics/top?limit=5" | jq
```
**Response — `200 OK`:**
```json
[
  { "shortCode": "21", "originalUrl": "https://www.example.com/...", "clickCount": 3 },
  { "shortCode": "g",  "originalUrl": "https://another-site.com/...", "clickCount": 1 }
]
```

### 4.5 Error examples
```bash
# 404 — unknown short code
curl -i http://localhost:8080/doesNotExist

# 400 — validation failure (blank URL)
curl -i -X POST http://localhost:8080/api/urls \
  -H "Content-Type: application/json" -d '{"originalUrl": ""}'

# 429 — rate limit exceeded (after exhausting app.rate-limit.capacity requests)
for i in $(seq 1 25); do curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8080/api/urls -X POST -H "Content-Type: application/json" -d '{"originalUrl":"https://example.com"}'; done
```
Every error follows the same shape (`ErrorResponse`):
```json
{
  "timestamp": "2026-06-20T10:25:00",
  "status": 404,
  "error": "Not Found",
  "message": "No URL mapping found for short code: doesNotExist",
  "path": "/doesNotExist",
  "details": null
}
```

---

## 5. Deployment Notes

- **Schema management:** this project uses `ddl-auto: update` for easy local
  demoing. A real production deployment should switch to `ddl-auto: validate`
  and manage the schema with a migration tool (Flyway/Liquibase), using
  `schema.sql` as the starting migration — `ddl-auto: update` should never
  touch a live production database directly (no audit trail, no rollback).
- **Secrets:** `DB_USERNAME`/`DB_PASSWORD`/`DB_URL` are all overridable via
  environment variables (see `application.yml`) — never hard-code real
  credentials; inject them via your platform's secret manager (AWS Secrets
  Manager, Kubernetes Secrets, etc.) at deploy time.
- **Rate limiting at scale:** the current `RateLimitConfig` keeps buckets in
  an in-memory `ConcurrentHashMap`, which only works correctly for a single
  app instance. For horizontal scaling (multiple instances behind a load
  balancer), back Bucket4j with a shared store such as Redis
  (`bucket4j-redis`) so every instance enforces the same shared quota per
  client.
- **Click-counter contention at extreme scale:** the pessimistic `FOR UPDATE`
  lock used to increment `click_count` guarantees correctness but serializes
  concurrent redirects of the *same* very popular link. At very large scale,
  a production system would instead increment counts asynchronously (e.g.
  publish a "click happened" event to a queue like Kafka/SQS and have a
  separate consumer batch-update counters), trading perfect real-time
  accuracy for much higher throughput.
- **Docker:** `docker compose up --build` runs the full stack (app + MySQL)
  locally exactly as it would in a container orchestrator like Kubernetes or
  ECS — the same `Dockerfile` image is what you'd push to a registry and
  deploy.

---

## 6. Interview Questions & Answers (per component)

### Entities (`UrlMapping`, `ClickEvent`)
**Q: Why is `shortCode` nullable on the entity if every URL must have one?**
A: We don't know the auto-increment `id` until after the row is inserted, and
the short code is derived from that `id`. We insert with `shortCode = null`
first, then immediately update it within the same transaction — see
`UrlServiceImpl.createShortUrl`.

**Q: Why does `UrlMapping` store an `originalUrlHash` column in addition to
`originalUrl` itself?**
A: `original_url` is a `VARCHAR(2048)`, which is too large to index directly
in InnoDB (its index key length is capped well below the worst-case size of
a `utf8mb4` `VARCHAR(2048)`). We instead store a fixed-length SHA-256 hash
of the URL and index *that*, making the "has this URL already been
shortened?" duplicate-check (run on every `POST /api/urls`) a fast indexed
lookup rather than a full table scan. We still defensively compare the full
`original_url` string too, so correctness never depends on hash collisions
being impossible — only performance does.

**Q: Why is the `ClickEvent` relationship `FetchType.LAZY`?**
A: A popular link can accumulate millions of click events. Eagerly loading
the entire collection every time we fetch a `UrlMapping` (e.g. on every
single redirect) would be a severe, unnecessary performance problem. We only
query `ClickEventRepository` explicitly when analytics actually need it.

### Base62 Encoding
**Q: Why Base62 instead of hashing the URL (e.g. MD5 truncated)?**
A: A hash of arbitrary input data can genuinely collide between two
different URLs, requiring a check-and-retry loop that gets slower as the
table fills. Base62-encoding a database-guaranteed-unique auto-increment ID
is a pure bijection — it mathematically cannot collide, with zero retry
logic needed.

**Q: Why not just use the raw decimal ID as the short code?**
A: It would work but produce longer codes for the same value, since Base62
needs far fewer characters to represent the same number than Base10 (more
symbols per "digit" = fewer digits needed). Shorter codes are the entire
point of a URL shortener.

**Q: Is short-code length predictable, and does this leak the URL count?**
A: Yes — codes grow roughly logarithmically with ID (a 5-character Base62
code covers ~916 million values). Sequential codes do reveal relative
creation order/volume. If unguessable codes were a hard requirement, an
alternative is to Base62-encode `id XOR a secret constant` or use a
Feistel-network-based ID obfuscation step before encoding — a worthwhile
follow-up discussion for a security-conscious interview.

### Concurrency / Locking
**Q: What race condition does `findByIdForUpdate`'s pessimistic lock prevent?**
A: A "lost update": without it, two simultaneous redirects of the same link
could both read `clickCount=5`, both independently compute `6`, and one
increment would be silently lost. `SELECT ... FOR UPDATE` takes a row lock
so the second transaction waits until the first commits.

**Q: What's the throughput trade-off of pessimistic locking here?**
A: It serializes concurrent redirects of the *same* short code (different
codes aren't affected — locks are per-row). For most links this is a total
non-issue; for a small number of viral, extremely hot links it could become
a bottleneck, which is why the README's "Scaling Considerations" describes
an async/event-driven alternative for that specific scenario.

### Rate Limiting (Bucket4j)
**Q: Why a token bucket instead of a fixed-window counter?**
A: A fixed window (e.g. "max 20 requests per calendar minute") can unfairly
allow a 2x burst right at a window boundary (20 requests at 0:59, another 20
at 1:00) and unfairly reject a legitimate short burst mid-window. A token
bucket smooths this by capping the *sustained average* rate while still
permitting brief bursts up to its capacity.

**Q: Why implement rate limiting as a Servlet `Filter` instead of inside the
controller or a Spring `HandlerInterceptor`?**
A: A `Filter` runs at the servlet-container level, before Spring's
`DispatcherServlet` does any routing — a rejected request never reaches
controller/validation/business logic at all, the cheapest possible point to
reject abusive traffic, and it applies uniformly without needing to
remember to add a check to every new endpoint.

**Q: What's the weakness of keying rate limits by IP address?**
A: Multiple legitimate users behind the same NAT/corporate proxy share one
IP and could be unfairly throttled together; conversely, a malicious client
without a trusted proxy in front could spoof `X-Forwarded-For` to evade
limits. A system with authentication would key by API key/user ID instead,
or combine both signals.

### Architecture / Layering
**Q: Why have separate `UrlService`/`AnalyticsService` interfaces with a
single implementation each, rather than just writing the impl classes
directly?**
A: Programming to an interface lets controllers depend on an abstraction
(easy to swap implementations later, e.g. add a caching decorator) and lets
controller tests inject a Mockito mock of the interface, fully isolating
HTTP-layer tests from real business logic.

**Q: Why are DTOs used instead of returning `@Entity` objects directly from
controllers?**
A: Separation of concerns (API shape can evolve independently of database
schema), security (prevents clients from mass-assigning fields like
`clickCount` or `id` that should never be client-controlled), and the
ability to include computed fields (like the full `shortUrl`) that don't
exist as a column at all.

**Q: Why is `open-in-view` disabled in `application.yml`?**
A: "Open Session In View" keeps a Hibernate session open for an entire HTTP
request, which hides N+1 query bugs and can hold database connections
longer than necessary under load. We instead ensure every entity field a
controller needs is fully loaded inside the `@Transactional` service method
before the response leaves that method.

### HTTP Semantics
**Q: Why HTTP 302 and not 301 for the redirect?**
A: `301 Moved Permanently` tells browsers to cache the redirect and stop
asking the server on future visits — which would silently stop our click
analytics from recording anything after a user's first visit. `302 Found`
means "temporary; ask again," ensuring every click reliably reaches our
server to be recorded.

---

## 7. Possible Extensions (out of scope here, good interview talking points)
- Custom/vanity short codes (user supplies their own alias).
- Expiring links (TTL column + scheduled cleanup job).
- Per-user authentication and ownership of links.
- Caching the hottest short codes in Redis in front of MySQL to reduce
  database load on the redirect hot path even further.
- Async click recording via a message queue for extreme-scale throughput.

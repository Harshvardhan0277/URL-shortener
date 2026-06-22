package com.example.urlshortener.service;

import com.example.urlshortener.dto.CreateUrlRequest;
import com.example.urlshortener.dto.CreateUrlResponse;
import com.example.urlshortener.entity.ClickEvent;
import com.example.urlshortener.entity.UrlMapping;
import com.example.urlshortener.exception.UrlNotFoundException;
import com.example.urlshortener.repository.ClickEventRepository;
import com.example.urlshortener.repository.UrlMappingRepository;
import com.example.urlshortener.util.Base62Encoder;
import com.example.urlshortener.util.UrlHasher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * ==============================================================================
 * UrlServiceImpl — the concrete implementation of UrlService; this is where
 * the actual BUSINESS LOGIC of the URL shortener lives.
 * ==============================================================================
 * @Service
 * ------------------------------------------------------------------------------
 * Marks this class as a Spring-managed bean belonging to the "service"
 * stereotype. Functionally @Service behaves identically to the generic
 * @Component, but using the more specific stereotype annotation:
 *   - Documents INTENT to future readers (and to tools/IDEs) — "this is
 *     business logic", as opposed to @Repository (persistence) or
 *     @RestController (web layer).
 *   - Makes this class eligible for component-scanning (see
 *     UrlShortenerApplication's @ComponentScan) so Spring instantiates it
 *     once at startup and injects it wherever it's @Autowired (here, via
 *     constructor injection into UrlController).
 * ==============================================================================
 */
@Service
public class UrlServiceImpl implements UrlService {

    private final UrlMappingRepository urlMappingRepository;
    private final ClickEventRepository clickEventRepository;

    // Injected from application.yml's `app.base-url` property — lets us
    // build the full shareable short URL (e.g. "http://localhost:8080/abc123")
    // without hard-coding the host/port, so the SAME code works correctly
    // in dev, staging, and production simply by changing one config value
    // (or the APP_BASE_URL environment variable — see application.yml).
    @Value("${app.base-url}")
    private String baseUrl;

    /**
     * CONSTRUCTOR INJECTION (rather than field injection with @Autowired
     * directly on the fields above):
     * ------------------------------------------------------------------------
     * Spring sees this is the ONLY constructor and automatically injects
     * matching beans (UrlMappingRepository and ClickEventRepository, both
     * auto-generated Spring Data JPA beans) as arguments when it
     * instantiates UrlServiceImpl — no @Autowired annotation is even
     * required on the constructor itself in modern Spring (single-
     * constructor classes get it implicitly).
     *
     * WHY THIS IS THE RECOMMENDED STYLE OVER FIELD INJECTION:
     *   - Fields can be made `final`, guaranteeing at compile time that
     *     dependencies are never null/reassigned after construction.
     *   - Makes the class trivially unit-testable WITHOUT Spring at all —
     *     a test can just call `new UrlServiceImpl(mockRepo1, mockRepo2)`
     *     directly (see UrlServiceImplTest), no Spring context needed.
     *   - Dependencies are explicit and visible in one place (the
     *     constructor signature) rather than scattered across field
     *     declarations.
     */
    public UrlServiceImpl(UrlMappingRepository urlMappingRepository,
                           ClickEventRepository clickEventRepository) {
        this.urlMappingRepository = urlMappingRepository;
        this.clickEventRepository = clickEventRepository;
    }

    /**
     * createShortUrl
     * ==========================================================================
     * STEP 0 (NEW): IDEMPOTENT DUPLICATE CHECK.
     * ==========================================================================
     * Before creating anything, we check whether this EXACT original URL has
     * already been shortened. If it has, we return the EXISTING mapping's
     * short code instead of minting a brand-new one — without this check,
     * POSTing the same URL twice would create two different short codes
     * pointing at the same destination, which wastes database rows and
     * (worse) silently fragments a single link's click analytics across
     * multiple short codes instead of accumulating them in one place.
     *
     * We look the URL up by its SHA-256 hash (see UrlHasher's Javadoc for
     * why we hash rather than indexing the raw 2048-char URL column
     * directly), then defensively re-compare the full original_url string
     * to guard against the astronomically unlikely case of a hash
     * collision between two different URLs.
     *
     * ==========================================================================
     * THE CORE DESIGN DECISION FOR *NEW* URLs: the "insert-then-update"
     * two-step pattern for generating Base62 short codes.
     * ==========================================================================
     * THE PROBLEM: Base62Encoder.encode() needs a NUMBER to encode — and the
     * number we want to encode is the database's own auto-increment id for
     * this new row. But we don't KNOW that id until AFTER the row has been
     * inserted (MySQL assigns AUTO_INCREMENT values at insert time, not
     * before).
     *
     * THE SOLUTION (exactly what this method does, in order, once we've
     * confirmed via Step 0 that this URL is genuinely new):
     *   STEP 1: Build a UrlMapping with originalUrl + createdAt, but
     *           shortCode left null, and save() it. Hibernate issues an
     *           INSERT; MySQL assigns the next AUTO_INCREMENT id and hands
     *           it back; Hibernate populates saved.getId() with that value.
     *
     *           GENERATED SQL:
     *               insert into url_mapping
     *                   (click_count, created_at, last_accessed_at, original_url,
     *                    original_url_hash, short_code)
     *               values (?, ?, ?, ?, ?, ?)
     *           (short_code parameter is bound as NULL on this first insert)
     *
     *   STEP 2: Now that we HAVE the id, compute shortCode =
     *           Base62Encoder.encode(id) and call setShortCode(...) on the
     *           SAME managed entity, then save() again.
     *
     *           GENERATED SQL (an UPDATE this time, because the entity
     *           already has a non-null id Hibernate recognizes as
     *           "already persisted"):
     *               update url_mapping set short_code=? where id=?
     *
     * WHY NOT AVOID THE SECOND ROUND-TRIP, e.g. BY HASHING THE URL STRING
     * INSTEAD?
     *   Hash-based approaches (e.g. MD5(url) truncated to 7 chars) CAN
     *   genuinely collide between two different URLs, forcing a
     *   "check-if-taken, regenerate, retry" loop whose worst case gets
     *   slower as the table fills up. Our AUTO_INCREMENT + Base62 approach
     *   trades one extra UPDATE statement (cheap, indexed by primary key)
     *   for a MATHEMATICAL GUARANTEE of zero collisions, ever, with no
     *   retry loop needed — a better trade-off for a system whose whole
     *   job is generating unique codes reliably under concurrent load.
     *   (We DO use a hash for the Step 0 duplicate-URL check above, but
     *   note that's a fundamentally different problem — there, a false
     *   "no match" just means we create one harmless extra row; it is NOT
     *   relied upon for short-code uniqueness, which still comes entirely
     *   from the AUTO_INCREMENT + Base62 mechanism described here.)
     *
     * @Transactional
     * ------------------------------------------------------------------------
     * Wraps this entire method — including the Step 0 lookup — in a single
     * database transaction. WHY THIS MATTERS HERE SPECIFICALLY: steps 1 and
     * 2 above are TWO separate SQL statements (INSERT then UPDATE) that
     * must succeed or fail TOGETHER — if step 2 somehow failed (e.g. a
     * transient DB error), we do NOT want to be left with a permanently
     * "orphaned" row that has an id but no short_code ever assigned.
     * @Transactional ensures: if any exception propagates out of this
     * method, EVERYTHING done so far (including the initial insert) is
     * rolled back automatically — Spring wraps the method call in
     * `BEGIN ... COMMIT` (or `ROLLBACK` on failure) at the database level.
     */
    @Override
    @Transactional
    public CreateUrlResult createShortUrl(CreateUrlRequest request) {
        String originalUrlHash = UrlHasher.sha256Hex(request.originalUrl());

        // STEP 0: has this exact URL already been shortened? If so, reuse
        // its existing short code instead of creating a duplicate row.
        Optional<UrlMapping> existing = urlMappingRepository
                .findFirstByOriginalUrlHashAndOriginalUrl(originalUrlHash, request.originalUrl());

        if (existing.isPresent()) {
            UrlMapping mapping = existing.get();
            CreateUrlResponse response = new CreateUrlResponse(
                    mapping.getShortCode(),
                    baseUrl + "/" + mapping.getShortCode(),
                    mapping.getOriginalUrl(),
                    mapping.getCreatedAt(),
                    mapping.getClickCount(),
                    mapping.getLastAccessedAt()
            );
            return new CreateUrlResult(response, true); // true = already existed, nothing new created
        }

        LocalDateTime now = LocalDateTime.now();

        // STEP 1: insert the row WITHOUT a short code yet, to obtain the
        // database-generated id.
        UrlMapping urlMapping = new UrlMapping(request.originalUrl(), originalUrlHash, now);
        UrlMapping saved = urlMappingRepository.save(urlMapping);
        // ^ At this point `saved.getId()` is guaranteed non-null: Hibernate
        // populated it from MySQL's AUTO_INCREMENT result immediately after
        // the INSERT executed (GenerationType.IDENTITY makes this happen
        // synchronously, unlike GenerationType.SEQUENCE which can batch).

        // STEP 2: now that we know the unique id, deterministically derive
        // the short code from it and persist that update.
        String shortCode = Base62Encoder.encode(saved.getId());

        // Defensive check (belt-and-suspenders alongside the database's
        // own UNIQUE constraint on short_code): since Base62 encoding of a
        // genuinely unique id is mathematically guaranteed unique, this
        // should NEVER actually trigger in correct operation — but
        // checking explicitly turns a hypothetical bug into a clear,
        // immediate, debuggable failure instead of a confusing database
        // constraint-violation exception surfacing from deep within
        // Hibernate.
        if (urlMappingRepository.existsByShortCode(shortCode)) {
            throw new IllegalStateException(
                    "Unexpected short code collision for code: " + shortCode +
                            " — this should be mathematically impossible with Base62(unique id); investigate immediately."
            );
        }

        saved.setShortCode(shortCode);
        UrlMapping updated = urlMappingRepository.save(saved);
        // GENERATED SQL: update url_mapping set short_code=? where id=?
        // (Hibernate's "dirty checking" would actually auto-flush this
        // change at transaction commit even without an explicit save()
        // call, since `saved` is a managed entity within this
        // @Transactional method — but we call save() explicitly here for
        // clarity and to make the two-step nature of this algorithm
        // obvious to a reader.)

        String fullShortUrl = baseUrl + "/" + updated.getShortCode();

        CreateUrlResponse response = new CreateUrlResponse(
                updated.getShortCode(),
                fullShortUrl,
                updated.getOriginalUrl(),
                updated.getCreatedAt(),
                updated.getClickCount(),
                updated.getLastAccessedAt()
        );
        return new CreateUrlResult(response, false); // false = a brand-new row was created
    }

    /**
     * resolveAndRecordClick
     * ==========================================================================
     * The core operation behind every redirect. Two responsibilities, both
     * inside ONE transaction:
     *   1. Look up the original URL for the given short code (404 if not found).
     *   2. Record analytics: bump the click counter, stamp last-accessed
     *      time, and insert a new ClickEvent audit row.
     * ==========================================================================
     */
    @Override
    @Transactional
    public String resolveAndRecordClick(String shortCode, String ipAddress, String userAgent) {
        // We use findByIdForUpdate is NOT applicable here since we don't yet
        // have the id — we must first resolve shortCode -> id via the
        // unique index, THEN (for the increment itself) we re-fetch with a
        // pessimistic lock by id to safely serialize concurrent increments
        // on the SAME row. See UrlMappingRepository for the full
        // explanation of why FOR UPDATE matters here.
        UrlMapping urlMapping = urlMappingRepository.findByShortCode(shortCode)
                .orElseThrow(() -> new UrlNotFoundException(shortCode));
        // GENERATED SQL: select ... from url_mapping where short_code = ?
        // (fast: uses the unique index on short_code)

        // Re-fetch the SAME row WITH a pessimistic write lock before
        // mutating clickCount, to prevent the "lost update" race condition
        // described in UrlMappingRepository.findByIdForUpdate's Javadoc.
        UrlMapping locked = urlMappingRepository.findByIdForUpdate(urlMapping.getId())
                .orElseThrow(() -> new UrlNotFoundException(shortCode));
        // GENERATED SQL: select ... from url_mapping where id = ? for update

        LocalDateTime now = LocalDateTime.now();

        // Mutate the in-memory entity...
        locked.incrementClickCount();
        locked.setLastAccessedAt(now);
        // ...Hibernate's "dirty checking" mechanism automatically detects
        // these field changes on this MANAGED entity and issues an UPDATE
        // at transaction commit — we do not need to call save() explicitly
        // for updates to an entity that's already attached to the current
        // persistence context (unlike the INSERT case in createShortUrl,
        // where the entity didn't exist in the DB/context yet).
        // GENERATED SQL (issued automatically at commit time):
        //     update url_mapping set click_count=?, last_accessed_at=? where id=?

        // Persist the per-click audit record.
        ClickEvent clickEvent = new ClickEvent(locked, now, ipAddress, userAgent);
        clickEventRepository.save(clickEvent);
        // GENERATED SQL:
        //     insert into click_event (accessed_at, ip_address, url_mapping_id, user_agent)
        //     values (?, ?, ?, ?)

        return locked.getOriginalUrl();
    }
}

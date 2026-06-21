package com.example.urlshortener.repository;

import com.example.urlshortener.entity.UrlMapping;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;

/**
 * ==============================================================================
 * UrlMappingRepository — the Repository layer for UrlMapping entities.
 * ==============================================================================
 * This is an INTERFACE with no implementation written by us at all. Spring
 * Data JPA generates a concrete implementation class at application startup
 * (via a dynamic proxy) by reading:
 *   (a) the method NAMES we declare (Spring parses them as a mini query
 *       language — "query derivation"), and
 *   (b) any explicit @Query annotations we add for cases too complex for
 *       name-derivation alone.
 *
 * WHY AN INTERFACE INSTEAD OF WRITING JDBC/SQL BY HAND?
 *   Boilerplate elimination: CRUD (Create/Read/Update/Delete) operations are
 *   ~90% identical across every entity in every app ever written. Spring
 *   Data JPA lets us state WHAT we want ("find a UrlMapping by its
 *   shortCode") and generates the HOW (the SQL, the ResultSet mapping, the
 *   connection handling) for us.
 *
 * extends JpaRepository<UrlMapping, Long>
 *   - UrlMapping : the entity type this repository manages.
 *   - Long       : the type of that entity's @Id field.
 *   This single line of inheritance gives us, FOR FREE, methods like:
 *     save(entity), findById(id), findAll(), deleteById(id), count(), etc.
 *   — each backed by a correct, tested SQL implementation we never had to
 *   write.
 * ==============================================================================
 */
public interface UrlMappingRepository extends JpaRepository<UrlMapping, Long> {

    /**
     * findByShortCode
     * ----------------------------------------------------------------------
     * METHOD NAME DERIVATION: Spring Data JPA parses the method name itself.
     * "findBy" + "ShortCode" tells it: "generate a query that selects from
     * url_mapping WHERE short_code = :shortCode".
     *
     * THE EXACT SQL HIBERNATE GENERATES AT RUNTIME (logged because
     * show-sql=true in application.yml):
     *
     *     select
     *         um1_0.id,
     *         um1_0.click_count,
     *         um1_0.created_at,
     *         um1_0.last_accessed_at,
     *         um1_0.original_url,
     *         um1_0.short_code
     *     from
     *         url_mapping um1_0
     *     where
     *         um1_0.short_code = ?
     *
     * This is THE single most frequently executed query in the whole
     * system (it runs on every redirect). It is fast because of the
     * UNIQUE INDEX on short_code defined in schema.sql / @Table(indexes=...)
     * — MySQL uses that index's B+Tree to jump straight to the matching
     * row instead of scanning the table.
     *
     * Returns Optional<UrlMapping> (rather than a raw UrlMapping that could
     * be null) so calling code is FORCED by the type system to explicitly
     * handle the "short code doesn't exist" case (see UrlServiceImpl, which
     * maps an empty Optional to a 404 UrlNotFoundException) instead of
     * risking a NullPointerException deep in business logic.
     */
    Optional<UrlMapping> findByShortCode(String shortCode);

    /**
     * existsByShortCode
     * ----------------------------------------------------------------------
     * Generates:
     *     select count(um1_0.id) > 0 (or an EXISTS-style query, depending on
     *     Hibernate version) from url_mapping um1_0 where um1_0.short_code = ?
     * Used defensively in UrlServiceImpl to double-check a freshly computed
     * Base62 code isn't somehow already taken before assigning it (belt and
     * suspenders alongside the database UNIQUE constraint).
     */
    boolean existsByShortCode(String shortCode);

    /**
     * findFirstByOriginalUrlHashAndOriginalUrl
     * ----------------------------------------------------------------------
     * THE DUPLICATE-URL CHECK: used by UrlServiceImpl.createShortUrl() to
     * answer "has this exact URL already been shortened?" BEFORE creating
     * a brand-new row — this is what makes POSTing the same URL twice
     * return the SAME short code both times, instead of wastefully
     * minting a new one each time.
     *
     * Method-name derivation: "findFirstBy" + "OriginalUrlHash" + "And" +
     * "OriginalUrl" generates a query filtering on BOTH columns, returning
     * at most one row ("First").
     *
     * WHY FILTER ON BOTH original_url_hash AND original_url, RATHER THAN
     * JUST THE HASH?
     *   We filter by original_url_hash FIRST because that's the indexed,
     *   fast part of the query (see idx_url_mapping_original_url_hash in
     *   schema.sql) — MySQL uses the index to jump straight to candidate
     *   rows instead of scanning the whole table. We ALSO compare the full
     *   original_url string as a defensive check against the
     *   astronomically unlikely event of a SHA-256 hash collision between
     *   two genuinely different URLs (see UrlHasher's Javadoc) — this way
     *   correctness never silently depends on "hash collisions don't
     *   happen," only performance does.
     *
     * GENERATED SQL:
     *     select um1_0.id, um1_0.click_count, um1_0.created_at,
     *            um1_0.last_accessed_at, um1_0.original_url,
     *            um1_0.original_url_hash, um1_0.short_code
     *     from url_mapping um1_0
     *     where um1_0.original_url_hash = ? and um1_0.original_url = ?
     *     limit 1
     */
    Optional<UrlMapping> findFirstByOriginalUrlHashAndOriginalUrl(String originalUrlHash, String originalUrl);

    /**
     * findTopNByOrderByClickCountDesc
     * ----------------------------------------------------------------------
     * Method-name derivation again: "findTopNBy" lets us cap the number of
     * results, "OrderByClickCountDesc" adds an ORDER BY clause.
     * We instead expose a Pageable-based version below for flexibility
     * (caller decides how many "top" results they want at call time rather
     * than it being baked into the method name) — see findTopUrls().
     *
     * THE GENERATED SQL (for the Pageable version below) looks like:
     *
     *     select
     *         um1_0.id, um1_0.click_count, um1_0.created_at,
     *         um1_0.last_accessed_at, um1_0.original_url, um1_0.short_code
     *     from
     *         url_mapping um1_0
     *     order by
     *         um1_0.click_count desc
     *     limit ?
     *
     * The LIMIT is added automatically by Spring Data JPA from the Pageable
     * argument's page size. This query benefits from the
     * idx_url_mapping_click_count index (declared DESC to match this exact
     * sort direction), letting MySQL read the top-N rows directly off the
     * index without an expensive in-memory filesort over the whole table.
     */
    @Query("SELECT u FROM UrlMapping u ORDER BY u.clickCount DESC")
    // ^ This is JPQL (Java Persistence Query Language) — note it references
    // the ENTITY name "UrlMapping" and its Java field "clickCount", NOT the
    // table/column names. Hibernate translates JPQL into the actual SQL
    // dialect (MySQL here) at runtime.
    List<UrlMapping> findTopUrls(Pageable pageable);
    // Pageable lets the CALLER specify "give me the top 10" or "top 50"
    // without us hard-coding a limit or writing a separate method per size.

    /**
     * findByIdForUpdate
     * ----------------------------------------------------------------------
     * Demonstrates PESSIMISTIC LOCKING, used when we increment click_count
     * on a redirect. Without this, two simultaneous redirects on the SAME
     * popular short code could both read clickCount=5, both compute 6, and
     * both write 6 back — losing one increment ("lost update" race
     * condition).
     *
     * @Lock(LockModeType.PESSIMISTIC_WRITE) tells Hibernate to append
     * `FOR UPDATE` to the generated SELECT, which asks MySQL's InnoDB
     * engine to take a row-level lock for the duration of the surrounding
     * @Transactional method — any other transaction trying to read this
     * SAME row with FOR UPDATE (or write to it) will block until we commit.
     *
     * GENERATED SQL:
     *     select um1_0.id, ... from url_mapping um1_0 where um1_0.id = ? for update
     *
     * TRADE-OFF discussed further in UrlServiceImpl: this guarantees
     * correctness for the click counter but serializes concurrent redirects
     * of the SAME link. For an extremely hot single link at very large
     * scale, a production system would instead batch increments
     * asynchronously (e.g. via a message queue) — documented as a follow-up
     * in the README "Scaling Considerations" section.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM UrlMapping u WHERE u.id = :id")
    Optional<UrlMapping> findByIdForUpdate(@Param("id") Long id);
}

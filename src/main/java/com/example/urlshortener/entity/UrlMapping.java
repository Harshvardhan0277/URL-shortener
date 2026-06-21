package com.example.urlshortener.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * ==============================================================================
 * UrlMapping — the JPA Entity representing one shortened URL.
 * ==============================================================================
 * An "Entity" in JPA is a plain Java class annotated so that Hibernate knows
 * how to translate it back and forth into rows of a database table. Every
 * field below corresponds to one column in the {@code url_mapping} table
 * (see schema.sql). This class lives in our "Entity layer" — the lowest
 * layer of our architecture, representing persistent state with NO business
 * logic and NO HTTP/JSON concerns (that separation is exactly why we have a
 * separate DTO layer — see the dto package).
 * ==============================================================================
 */
@Entity
// @Entity tells Hibernate "this class maps to a database table; manage its
// lifecycle (insert/update/delete/select) for me."

@Table(
        name = "url_mapping",
        indexes = {
                // This mirrors schema.sql: when ddl-auto=update creates/validates
                // the table, Hibernate will also create this index definition.
                // Declaring it here (in addition to schema.sql) means the index
                // exists correctly EVEN if a developer only ever runs the app
                // with ddl-auto=update and never looks at schema.sql.
                @Index(name = "idx_url_mapping_click_count", columnList = "clickCount DESC"),

                // Index on the hash column (NOT on original_url itself — see
                // UrlHasher's Javadoc for why a 2048-char column can't be
                // indexed directly in MySQL). This is what makes the
                // "does this URL already have a short code?" duplicate check
                // in UrlServiceImpl.createShortUrl() a fast indexed lookup
                // instead of a full table scan.
                @Index(name = "idx_url_mapping_original_url_hash", columnList = "original_url_hash")
        }
)
// @Table lets us customize the physical table name/details. If we omitted
// this, Hibernate would default to using the class name ("UrlMapping") as
// the table name, which is usually not the snake_case convention SQL teams
// prefer.
public class UrlMapping {

    @Id
    // @Id marks this field as the table's PRIMARY KEY.
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    // GenerationType.IDENTITY delegates ID generation to the database's own
    // AUTO_INCREMENT column. WHY THIS MATTERS FOR OUR DESIGN:
    // our entire Base62 short-code scheme depends on having a globally
    // unique, monotonically increasing integer for every new row — and
    // letting MySQL's AUTO_INCREMENT counter generate it is the simplest,
    // most battle-tested way to get that guarantee even under heavy
    // concurrent writes (MySQL serializes AUTO_INCREMENT allocation
    // internally, so two simultaneous inserts can NEVER receive the same
    // id). We then Base62-encode this id in UrlServiceImpl to produce the
    // public-facing short code.
    @Column(name = "id")
    private Long id;

    @Column(name = "original_url", nullable = false, length = 2048)
    // length=2048 matches the VARCHAR(2048) in schema.sql — long enough for
    // virtually any real-world URL (browsers themselves cap around 2000-8000
    // chars depending on vendor).
    private String originalUrl;

    @Column(name = "original_url_hash", nullable = false, length = 64)
    // The SHA-256 hash (as 64 hex characters) of originalUrl — see
    // UrlHasher's Javadoc for why this exists: it lets us index a
    // FIXED-LENGTH value instead of the full 2048-char URL, making
    // duplicate-URL lookups (UrlServiceImpl.createShortUrl) fast.
    // length=64 matches the CHAR(64) declared in schema.sql.
    private String originalUrlHash;

    @Column(name = "short_code", unique = true, length = 10)
    // unique=true tells Hibernate to also generate a UNIQUE constraint when
    // creating this column (defense in depth alongside our explicit index
    // in schema.sql). Nullable on purpose: see UrlServiceImpl for why we
    // insert a row WITHOUT a short_code first, then fill it in once we know
    // the generated id.
    private String shortCode;

    @Column(name = "click_count", nullable = false)
    private Long clickCount = 0L;
    // Defaulted to 0 in Java so that brand-new UrlMapping objects are always
    // valid even before Hibernate assigns them to the database default.

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "last_accessed_at")
    private LocalDateTime lastAccessedAt;
    // Nullable: a freshly created short URL that nobody has clicked yet has
    // no "last accessed" time.

    @OneToMany(mappedBy = "urlMapping", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    // @OneToMany : one UrlMapping can have MANY ClickEvent rows.
    // mappedBy="urlMapping" : tells Hibernate the FOREIGN KEY column lives on
    //   the ClickEvent side (its `urlMapping` field), so url_mapping does NOT
    //   get an extra join-table or foreign-key column itself — this is the
    //   "owning side" inversion that keeps our schema clean (matches the
    //   url_mapping_id column directly on click_event in schema.sql).
    // cascade=ALL : if we ever delete a UrlMapping via Hibernate, its
    //   ClickEvents are deleted too (mirrors ON DELETE CASCADE in SQL).
    // fetch=LAZY : DO NOT load all click events every time we load a
    //   UrlMapping. Click history can grow to millions of rows for popular
    //   links; loading them eagerly on every redirect lookup would be a
    //   severe and unnecessary performance problem. We only ever query
    //   ClickEvents explicitly through ClickEventRepository when we actually
    //   need analytics — this list is rarely touched directly.
    private List<ClickEvent> clickEvents = new ArrayList<>();

    /**
     * JPA REQUIRES a no-argument constructor (protected/public) so that
     * Hibernate can instantiate entities via reflection BEFORE populating
     * their fields from a ResultSet row.
     */
    protected UrlMapping() {
    }

    /**
     * The constructor our application code actually uses when creating a
     * brand-new short URL. Note: it deliberately does NOT take a shortCode
     * or id — those are filled in only after the database assigns the
     * auto-increment id (see UrlServiceImpl.createShortUrl).
     *
     * Takes originalUrlHash as an explicit parameter (rather than computing
     * it internally via UrlHasher) to keep this entity free of any
     * algorithm-specific logic — hashing is a UTILITY concern, computed
     * once by the caller (UrlServiceImpl) and handed in, the same way
     * createdAt is computed by the caller rather than by the entity
     * calling LocalDateTime.now() itself. This keeps the entity a simple,
     * predictable data holder and keeps all the "how do we hash a URL"
     * logic in exactly one place (UrlHasher).
     */
    public UrlMapping(String originalUrl, String originalUrlHash, LocalDateTime createdAt) {
        this.originalUrl = originalUrl;
        this.originalUrlHash = originalUrlHash;
        this.createdAt = createdAt;
        this.clickCount = 0L;
    }

    // --------------------------------------------------------------------
    // Plain getters/setters below. We write these explicitly (no Lombok)
    // so every field's access is visible and easy to follow for learning
    // purposes — Hibernate calls these via reflection to read/write entity
    // state when loading/saving rows.
    // --------------------------------------------------------------------

    public Long getId() {
        return id;
    }

    public String getOriginalUrl() {
        return originalUrl;
    }

    public void setOriginalUrl(String originalUrl) {
        this.originalUrl = originalUrl;
    }

    public String getOriginalUrlHash() {
        return originalUrlHash;
    }

    public String getShortCode() {
        return shortCode;
    }

    public void setShortCode(String shortCode) {
        this.shortCode = shortCode;
    }

    public Long getClickCount() {
        return clickCount;
    }

    public void setClickCount(Long clickCount) {
        this.clickCount = clickCount;
    }

    /**
     * Convenience domain method (business logic that legitimately belongs
     * ON the entity, since it only touches the entity's own state) used by
     * UrlServiceImpl every time a redirect happens.
     */
    public void incrementClickCount() {
        this.clickCount = (this.clickCount == null ? 0L : this.clickCount) + 1;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getLastAccessedAt() {
        return lastAccessedAt;
    }

    public void setLastAccessedAt(LocalDateTime lastAccessedAt) {
        this.lastAccessedAt = lastAccessedAt;
    }

    public List<ClickEvent> getClickEvents() {
        return clickEvents;
    }
}

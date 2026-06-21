-- ==============================================================================
-- schema.sql — Reference DDL (Data Definition Language) for MySQL
-- ==============================================================================
-- WHY THIS FILE EXISTS EVEN THOUGH HIBERNATE CAN AUTO-CREATE TABLES:
-- In application.yml we set `ddl-auto: update`, which is convenient for local
-- development because Hibernate reads our @Entity classes and creates/updates
-- matching tables automatically. That is FINE for a demo, but a production
-- team would NEVER let an ORM auto-mutate a live production schema (no audit
-- trail, no safe rollback, risk of accidental data loss on a misread mapping).
--
-- This file is what a real team would hand to a migration tool such as
-- Flyway or Liquibase (e.g. saved as V1__init.sql) and run with
-- `ddl-auto: validate`, so Hibernate only CHECKS the schema matches instead
-- of changing it. It is not auto-executed by Spring Boot in this project
-- (Spring Boot only auto-runs files literally named schema.sql/data.sql when
-- `spring.sql.init.mode` is enabled — we leave that off so JPA's ddl-auto
-- is the single source of truth for this demo). Keep this file as the
-- "documentation of intent" / production migration starting point.
-- ==============================================================================

CREATE DATABASE IF NOT EXISTS url_shortener
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

USE url_shortener;

-- ------------------------------------------------------------------------------
-- TABLE: url_mapping
-- ------------------------------------------------------------------------------
-- One row per shortened URL. The auto-increment `id` is the foundation of our
-- whole short-code strategy: we let MySQL hand us a guaranteed-unique integer
-- for free (via AUTO_INCREMENT), then convert THAT integer to Base62 in Java.
-- This means we get collision-free short codes with zero coordination/locking
-- logic of our own — MySQL's auto-increment mechanism already solved
-- "uniqueness under concurrency" for us.
-- ------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS url_mapping (
    id                BIGINT          NOT NULL AUTO_INCREMENT,
    original_url      VARCHAR(2048)   NOT NULL,        -- 2048 chars is the de-facto safe max URL length
    original_url_hash CHAR(64)        NOT NULL,         -- SHA-256 hex digest of original_url, used for fast duplicate-URL lookups (see UrlHasher.java) since the full 2048-char column itself is too large to index directly in InnoDB
    short_code        VARCHAR(10)     NULL,             -- nullable initially; filled in right after insert (see UrlServiceImpl)
    click_count        BIGINT          NOT NULL DEFAULT 0,
    created_at        DATETIME(6)     NOT NULL,
    last_accessed_at  DATETIME(6)     NULL,

    PRIMARY KEY (id),

    -- UNIQUE INDEX on short_code:
    --   1) Enforces at the DATABASE level that two rows can never share a
    --      short code, even under concurrent inserts (defense in depth,
    --      doesn't rely solely on our Base62(id) logic being correct).
    --   2) A UNIQUE index is also a regular index, so this single index
    --      serves BOTH purposes: integrity AND fast lookups.
    -- WHY THIS MATTERS FOR PERFORMANCE:
    --   The hottest query in this whole system is the redirect lookup:
    --       SELECT * FROM url_mapping WHERE short_code = ?
    --   Without an index, MySQL would do a full table scan (O(n)) on every
    --   single redirect. With this index, MySQL uses a B+Tree to find the
    --   row in O(log n) time — the difference between milliseconds and
    --   seconds once the table has millions of rows.
    UNIQUE KEY uk_short_code (short_code),

    -- Index on original_url_hash: makes "has this exact URL already been
    -- shortened?" (checked on every POST /api/urls, BEFORE creating a new
    -- row) a fast indexed lookup instead of a full table scan over
    -- potentially millions of rows. See UrlHasher.java for why we index a
    -- hash of the URL rather than the URL column itself.
    KEY idx_url_mapping_original_url_hash (original_url_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ------------------------------------------------------------------------------
-- TABLE: click_event
-- ------------------------------------------------------------------------------
-- One row per individual click/redirect. Kept separate from url_mapping
-- (rather than cramming click history into that table) so that:
--   - url_mapping stays small and fast to scan/index (it's read on EVERY
--     redirect, so it must stay lean).
--   - We can store unlimited click history without ever touching/locking
--     the url_mapping row itself except for the lightweight counter bump.
--   - This is a classic "hot table / cold table" split, a common real-world
--     pattern for systems with high write-then-rarely-read audit data.
-- ------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS click_event (
    id              BIGINT        NOT NULL AUTO_INCREMENT,
    url_mapping_id  BIGINT        NOT NULL,
    accessed_at     DATETIME(6)   NOT NULL,
    ip_address      VARCHAR(45)   NULL,   -- 45 chars is enough for the longest possible IPv6 literal
    user_agent      VARCHAR(512)  NULL,

    PRIMARY KEY (id),

    -- Foreign key keeps referential integrity: a click_event can never point
    -- to a url_mapping row that doesn't exist. ON DELETE CASCADE means if a
    -- short URL is ever deleted, its click history is cleanly removed too
    -- instead of becoming orphaned rows.
    CONSTRAINT fk_click_event_url_mapping
        FOREIGN KEY (url_mapping_id) REFERENCES url_mapping (id)
        ON DELETE CASCADE,

    -- Index on the foreign key: our analytics queries ("total clicks for
    -- short code X", "all clicks for this URL ordered by time") always
    -- filter by url_mapping_id first, so this index avoids a full scan of
    -- (potentially huge) click_event table.
    KEY idx_click_event_url_mapping_id (url_mapping_id),

    -- Index on accessed_at: supports "most recent clicks" / time-windowed
    -- analytics queries efficiently (e.g. clicks in the last 24 hours).
    KEY idx_click_event_accessed_at (accessed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ------------------------------------------------------------------------------
-- A composite index to make "top URLs by click count" cheap, since that
-- analytics query sorts url_mapping by click_count descending.
-- ------------------------------------------------------------------------------
CREATE INDEX idx_url_mapping_click_count ON url_mapping (click_count DESC);

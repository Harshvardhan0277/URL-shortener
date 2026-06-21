package com.example.urlshortener.repository;

import com.example.urlshortener.entity.ClickEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * ==============================================================================
 * ClickEventRepository — the Repository layer for ClickEvent entities.
 * ==============================================================================
 * Handles persistence and querying of the per-click audit log. Kept as its
 * own repository (rather than nesting click-event queries inside
 * UrlMappingRepository) because it manages a DIFFERENT entity/aggregate
 * root — a clean Spring Data JPA repository is conventionally one per
 * entity type.
 * ==============================================================================
 */
public interface ClickEventRepository extends JpaRepository<ClickEvent, Long> {

    /**
     * findByUrlMappingIdOrderByAccessedAtDesc
     * ----------------------------------------------------------------------
     * Method-name derivation: "findBy" + "UrlMappingId" (Spring Data JPA is
     * smart enough to traverse the urlMapping.id nested property even
     * though urlMapping is a related entity, not a direct column) +
     * "OrderByAccessedAtDesc".
     *
     * GENERATED SQL:
     *     select
     *         ce1_0.id, ce1_0.accessed_at, ce1_0.ip_address,
     *         ce1_0.url_mapping_id, ce1_0.user_agent
     *     from
     *         click_event ce1_0
     *     where
     *         ce1_0.url_mapping_id = ?
     *     order by
     *         ce1_0.accessed_at desc
     *
     * Benefits from BOTH indexes declared on click_event: the WHERE clause
     * uses idx_click_event_url_mapping_id, and (in MySQL 8 with InnoDB) the
     * ORDER BY can additionally benefit from idx_click_event_accessed_at
     * once filtered.
     *
     * Not currently exposed via a controller endpoint in this project (kept
     * for future "full click history for a link" feature), but included to
     * demonstrate the pattern and because a real analytics service would
     * need it.
     */
    List<ClickEvent> findByUrlMappingIdOrderByAccessedAtDesc(Long urlMappingId);

    /**
     * countByUrlMappingId
     * ----------------------------------------------------------------------
     * An alternative, independently-verifiable way to compute total clicks
     * by COUNTING actual click_event rows, rather than trusting the
     * denormalized url_mapping.click_count counter. We expose this mainly
     * for analytics/auditing/testing — comparing this count against
     * url_mapping.click_count is a useful sanity check that the counter
     * hasn't drifted (e.g. due to a bug or manual DB edit).
     *
     * GENERATED SQL:
     *     select count(ce1_0.id) from click_event ce1_0 where ce1_0.url_mapping_id = ?
     */
    long countByUrlMappingId(Long urlMappingId);

    /**
     * findMostRecentAccessTime
     * ----------------------------------------------------------------------
     * Custom JPQL query (method-name derivation can't express "MAX of a
     * field" cleanly here) used as an alternative source-of-truth for
     * "last accessed" if we ever wanted to derive it from the event log
     * instead of url_mapping.last_accessed_at directly.
     *
     * GENERATED SQL:
     *     select max(ce1_0.accessed_at) from click_event ce1_0 where ce1_0.url_mapping_id = ?
     */
    @Query("SELECT MAX(c.accessedAt) FROM ClickEvent c WHERE c.urlMapping.id = :urlMappingId")
    java.time.LocalDateTime findMostRecentAccessTime(@Param("urlMappingId") Long urlMappingId);
}

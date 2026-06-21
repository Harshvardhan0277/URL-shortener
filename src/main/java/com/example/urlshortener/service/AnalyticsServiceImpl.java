package com.example.urlshortener.service;

import com.example.urlshortener.dto.TopUrlResponse;
import com.example.urlshortener.dto.UrlAnalyticsResponse;
import com.example.urlshortener.entity.UrlMapping;
import com.example.urlshortener.exception.UrlNotFoundException;
import com.example.urlshortener.repository.UrlMappingRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * ==============================================================================
 * AnalyticsServiceImpl — implements all read-only reporting queries.
 * ==============================================================================
 */
@Service
public class AnalyticsServiceImpl implements AnalyticsService {

    private final UrlMappingRepository urlMappingRepository;

    public AnalyticsServiceImpl(UrlMappingRepository urlMappingRepository) {
        this.urlMappingRepository = urlMappingRepository;
    }

    /**
     * getAnalytics
     * ------------------------------------------------------------------------
     * @Transactional(readOnly = true)
     * --------------------------------------------------------------------
     * readOnly=true is a HINT to both Hibernate and the underlying JDBC
     * driver/database that NO writes will occur in this transaction:
     *   - Hibernate can skip "dirty checking" (the bookkeeping it normally
     *     does to detect changed fields that need to be UPDATEd at commit),
     *     a small but real performance win for read-heavy endpoints like
     *     analytics.
     *   - Some JDBC drivers/connection pools can route read-only
     *     transactions to a read-replica database automatically in more
     *     advanced setups.
     * We still wrap this in a transaction at all (rather than no
     * transaction) so that, if getAnalytics were ever extended to touch a
     * LAZY-loaded collection (like urlMapping.getClickEvents()), the
     * Hibernate session stays open long enough to fetch it without an
     * exception — even though our current implementation doesn't touch
     * that collection.
     */
    @Override
    @Transactional(readOnly = true)
    public UrlAnalyticsResponse getAnalytics(String shortCode) {
        UrlMapping urlMapping = urlMappingRepository.findByShortCode(shortCode)
                .orElseThrow(() -> new UrlNotFoundException(shortCode));
        // GENERATED SQL: select ... from url_mapping where short_code = ?

        return new UrlAnalyticsResponse(
                urlMapping.getShortCode(),
                urlMapping.getOriginalUrl(),
                urlMapping.getClickCount(),
                urlMapping.getCreatedAt(),
                urlMapping.getLastAccessedAt()
        );
    }

    /**
     * getTopUrls
     * ------------------------------------------------------------------------
     * Delegates to UrlMappingRepository.findTopUrls(Pageable), which issues:
     *     select ... from url_mapping order by click_count desc limit ?
     *
     * PageRequest.of(0, limit) builds a Pageable requesting page index 0
     * (the first page) with a page size of `limit` — Spring Data JPA
     * translates this directly into the SQL LIMIT clause shown above. We
     * deliberately don't expose true pagination (page 2, 3, ...) via the
     * API here since "top N" leaderboards conventionally only care about
     * the first page; the Pageable mechanism is simply a convenient,
     * idiomatic way to express "give me only the first `limit` rows" to
     * Spring Data JPA.
     */
    @Override
    @Transactional(readOnly = true)
    public List<TopUrlResponse> getTopUrls(int limit) {
        Pageable pageable = PageRequest.of(0, limit);
        List<UrlMapping> topMappings = urlMappingRepository.findTopUrls(pageable);

        return topMappings.stream()
                .map(m -> new TopUrlResponse(m.getShortCode(), m.getOriginalUrl(), m.getClickCount()))
                .toList();
    }
}

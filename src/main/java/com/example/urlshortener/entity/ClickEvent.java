package com.example.urlshortener.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * ==============================================================================
 * ClickEvent — the JPA Entity representing a single redirect/click on a
 * shortened URL.
 * ==============================================================================
 * Every time someone hits GET /{shortCode} and gets redirected, we persist
 * one row of this entity. This gives us a full audit trail/history that the
 * aggregate `click_count` counter on UrlMapping alone cannot provide (e.g.
 * "show me every click in the last 24 hours with IP and browser info").
 *
 * DESIGN DECISION — why a separate table instead of just the counter?
 *   The `click_count` field on UrlMapping answers "how many clicks total?"
 *   in O(1) (just read one integer). But product/analytics requirements
 *   like "top referrers", "clicks per day", "which IPs/browsers are
 *   hitting this link" need PER-CLICK detail, which only a separate
 *   append-only event table can provide without bloating the hot
 *   url_mapping row.
 * ==============================================================================
 */
@Entity
@Table(
        name = "click_event",
        indexes = {
                @Index(name = "idx_click_event_url_mapping_id", columnList = "url_mapping_id"),
                @Index(name = "idx_click_event_accessed_at", columnList = "accessed_at")
        }
)
public class ClickEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    // @ManyToOne : many ClickEvent rows point back to ONE UrlMapping.
    // fetch=LAZY : when we load a ClickEvent, do NOT automatically also
    //   load its parent UrlMapping from the database unless we explicitly
    //   navigate to it. Avoids unnecessary joins/queries for analytics
    //   queries that only need click_event's own columns.
    @JoinColumn(name = "url_mapping_id", nullable = false, foreignKey = @ForeignKey(name = "fk_click_event_url_mapping"))
    // @JoinColumn : this is the actual FOREIGN KEY column physically stored
    // on the click_event table, pointing at url_mapping.id. This is the
    // "owning side" of the relationship (the mappedBy on UrlMapping.clickEvents
    // refers back to THIS field).
    private UrlMapping urlMapping;

    @Column(name = "accessed_at", nullable = false)
    private LocalDateTime accessedAt;

    @Column(name = "ip_address", length = 45)
    // 45 chars accommodates the longest possible IPv6 address representation.
    private String ipAddress;

    @Column(name = "user_agent", length = 512)
    private String userAgent;

    /** Required no-arg constructor for Hibernate's reflection-based instantiation. */
    protected ClickEvent() {
    }

    /**
     * Constructor used by application code (UrlServiceImpl) every time a
     * redirect happens — we capture exactly what an analytics dashboard
     * would want to know about that single click.
     */
    public ClickEvent(UrlMapping urlMapping, LocalDateTime accessedAt, String ipAddress, String userAgent) {
        this.urlMapping = urlMapping;
        this.accessedAt = accessedAt;
        this.ipAddress = ipAddress;
        this.userAgent = userAgent;
    }

    public Long getId() {
        return id;
    }

    public UrlMapping getUrlMapping() {
        return urlMapping;
    }

    public LocalDateTime getAccessedAt() {
        return accessedAt;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public String getUserAgent() {
        return userAgent;
    }
}

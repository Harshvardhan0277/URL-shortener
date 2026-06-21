package com.example.urlshortener.service;

import com.example.urlshortener.dto.CreateUrlRequest;
import com.example.urlshortener.entity.UrlMapping;
import com.example.urlshortener.exception.UrlNotFoundException;
import com.example.urlshortener.repository.ClickEventRepository;
import com.example.urlshortener.repository.UrlMappingRepository;
import com.example.urlshortener.util.UrlHasher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * ==============================================================================
 * UrlServiceImplTest — a pure UNIT test of the service layer's business
 * logic, with NO Spring context and NO real database involved at all.
 * ==============================================================================
 * @ExtendWith(MockitoExtension.class)
 * ------------------------------------------------------------------------------
 * Activates Mockito's JUnit 5 integration, which processes the @Mock
 * annotations below (creating fake/mock implementations of our repository
 * interfaces) and injects them wherever we manually wire them up in
 * setUp().
 *
 * WHY THIS TEST DOES *NOT* USE @SpringBootTest:
 *   @SpringBootTest would start an entire real Spring ApplicationContext
 *   (slow — seconds per test class) and typically a real or embedded
 *   database. Since UrlServiceImpl's dependencies are constructor-injected
 *   interfaces (see UrlServiceImpl's Javadoc), we can test its business
 *   logic in complete isolation by handing it Mockito-generated FAKE
 *   repositories — this test runs in milliseconds and tests EXACTLY one
 *   unit of behavior, with deterministic, fully-controlled inputs. This is
 *   the textbook benefit of constructor injection + programming to
 *   interfaces.
 * ==============================================================================
 */
@ExtendWith(MockitoExtension.class)
class UrlServiceImplTest {

    @Mock
    private UrlMappingRepository urlMappingRepository;

    @Mock
    private ClickEventRepository clickEventRepository;

    private UrlServiceImpl urlService;

    @BeforeEach
    void setUp() {
        // Manual construction (not @InjectMocks) to make the dependency
        // wiring fully explicit and easy to follow for a reader new to
        // the codebase.
        urlService = new UrlServiceImpl(urlMappingRepository, clickEventRepository);
        // @Value-injected fields (baseUrl) aren't populated by Spring in a
        // plain unit test, since there's no Spring context here at all —
        // we set it directly via reflection, exactly mirroring what
        // Spring would have done from application.yml.
        ReflectionTestUtils.setField(urlService, "baseUrl", "http://localhost:8080");
    }

    /**
     * Verifies the core "insert-then-update" Base62 generation strategy
     * documented in UrlServiceImpl.createShortUrl(): given a mock
     * repository that simulates (a) no existing row for this URL yet, and
     * (b) MySQL assigning id=125 on the first save(), the resulting short
     * code MUST be the known correct Base62 encoding of 125 ("21" — see
     * Base62Encoder's own Javadoc walkthrough for this exact example).
     */
    @Test
    void createShortUrl_generatesCorrectBase62CodeFromGeneratedId_whenUrlIsNew() {
        // ARRANGE: no existing row for this URL (the duplicate-check, Step 0,
        // finds nothing).
        when(urlMappingRepository.findFirstByOriginalUrlHashAndOriginalUrl(anyString(), anyString()))
                .thenReturn(Optional.empty());

        // ARRANGE: simulate the database assigning id=125 to the first save() call.
        when(urlMappingRepository.save(any(UrlMapping.class))).thenAnswer(invocation -> {
            UrlMapping arg = invocation.getArgument(0);
            if (arg.getId() == null) {
                // Simulate Hibernate populating the id after a real INSERT
                // by using reflection (mirrors GenerationType.IDENTITY's
                // real-world behavior, since we have no real database here).
                ReflectionTestUtils.setField(arg, "id", 125L);
            }
            return arg;
        });
        when(urlMappingRepository.existsByShortCode("21")).thenReturn(false);

        CreateUrlRequest request = new CreateUrlRequest("https://www.example.com/some/long/path");

        // ACT
        CreateUrlResult result = urlService.createShortUrl(request);

        // ASSERT
        assertThat(result.alreadyExisted()).isFalse(); // a brand-new row was created
        assertThat(result.response().shortCode()).isEqualTo("21");
        assertThat(result.response().shortUrl()).isEqualTo("http://localhost:8080/21");
        assertThat(result.response().originalUrl()).isEqualTo("https://www.example.com/some/long/path");

        // Verify save() was called exactly twice: once for the initial
        // insert (shortCode still null), once for the update that sets
        // the computed shortCode — proving the two-step pattern actually
        // executed as designed.
        verify(urlMappingRepository, times(2)).save(any(UrlMapping.class));
    }

    /**
     * THE DUPLICATE-URL FIX: verifies that POSTing a URL that has ALREADY
     * been shortened before returns the EXISTING short code — and does
     * NOT create a new row at all (save() must never be called).
     */
    @Test
    void createShortUrl_returnsExistingShortCode_whenUrlWasAlreadyShortened() {
        String originalUrl = "https://www.example.com/already/shortened";
        String hash = UrlHasher.sha256Hex(originalUrl);

        UrlMapping existingMapping = new UrlMapping(originalUrl, hash, LocalDateTime.now().minusDays(2));
        ReflectionTestUtils.setField(existingMapping, "id", 99L);
        ReflectionTestUtils.setField(existingMapping, "shortCode", "1b");

        when(urlMappingRepository.findFirstByOriginalUrlHashAndOriginalUrl(hash, originalUrl))
                .thenReturn(Optional.of(existingMapping));

        CreateUrlRequest request = new CreateUrlRequest(originalUrl);

        // ACT
        CreateUrlResult result = urlService.createShortUrl(request);

        // ASSERT
        assertThat(result.alreadyExisted()).isTrue(); // existing mapping was reused
        assertThat(result.response().shortCode()).isEqualTo("1b");
        assertThat(result.response().shortUrl()).isEqualTo("http://localhost:8080/1b");
        assertThat(result.response().originalUrl()).isEqualTo(originalUrl);

        // Crucially: no new row should ever have been created for a
        // duplicate URL.
        verify(urlMappingRepository, never()).save(any(UrlMapping.class));
    }

    /**
     * Verifies resolveAndRecordClick correctly throws UrlNotFoundException
     * (which GlobalExceptionHandler maps to HTTP 404) when the short code
     * doesn't exist — WITHOUT ever attempting to record a click.
     */
    @Test
    void resolveAndRecordClick_throwsNotFound_whenShortCodeDoesNotExist() {
        when(urlMappingRepository.findByShortCode("doesNotExist")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> urlService.resolveAndRecordClick("doesNotExist", "127.0.0.1", "JUnit"))
                .isInstanceOf(UrlNotFoundException.class)
                .hasMessageContaining("doesNotExist");

        // Confirm we never even attempted to persist a ClickEvent for a
        // link that doesn't exist.
        verify(clickEventRepository, never()).save(any());
    }

    /**
     * Verifies resolveAndRecordClick increments the click counter, stamps
     * last-accessed time, and persists a ClickEvent with the correct
     * IP/User-Agent — the full "happy path" of a redirect.
     */
    @Test
    void resolveAndRecordClick_incrementsCounterAndRecordsClickEvent() {
        String originalUrl = "https://example.com";
        UrlMapping existing = new UrlMapping(originalUrl, UrlHasher.sha256Hex(originalUrl), LocalDateTime.now().minusDays(1));
        ReflectionTestUtils.setField(existing, "id", 42L);
        ReflectionTestUtils.setField(existing, "shortCode", "g");
        ReflectionTestUtils.setField(existing, "clickCount", 4L);

        when(urlMappingRepository.findByShortCode("g")).thenReturn(Optional.of(existing));
        when(urlMappingRepository.findByIdForUpdate(42L)).thenReturn(Optional.of(existing));

        String result = urlService.resolveAndRecordClick("g", "203.0.113.5", "Mozilla/5.0");

        assertThat(result).isEqualTo("https://example.com");
        assertThat(existing.getClickCount()).isEqualTo(5L); // 4 -> 5
        assertThat(existing.getLastAccessedAt()).isNotNull();

        ArgumentCaptor<com.example.urlshortener.entity.ClickEvent> captor =
                ArgumentCaptor.forClass(com.example.urlshortener.entity.ClickEvent.class);
        verify(clickEventRepository).save(captor.capture());
        assertThat(captor.getValue().getIpAddress()).isEqualTo("203.0.113.5");
        assertThat(captor.getValue().getUserAgent()).isEqualTo("Mozilla/5.0");
    }
}

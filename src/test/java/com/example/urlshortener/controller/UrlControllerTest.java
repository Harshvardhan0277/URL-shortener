package com.example.urlshortener.controller;

import com.example.urlshortener.dto.CreateUrlRequest;
import com.example.urlshortener.dto.CreateUrlResponse;
import com.example.urlshortener.exception.UrlNotFoundException;
import com.example.urlshortener.service.CreateUrlResult;
import com.example.urlshortener.service.UrlService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * ==============================================================================
 * UrlControllerTest — an INTEGRATION test of the web layer only, using
 * Spring's MockMvc to simulate real HTTP requests WITHOUT starting an
 * actual network server or database.
 * ==============================================================================
 * @WebMvcTest(UrlController.class)
 * ------------------------------------------------------------------------------
 * A SLICE test annotation: it boots up only the Spring MVC infrastructure
 * relevant to web-layer testing (DispatcherServlet, @ControllerAdvice
 * classes like our GlobalExceptionHandler, JSON message converters, the
 * RateLimitFilter, validation, etc.) and registers ONLY the specified
 * controller (UrlController) — it deliberately does NOT load @Service,
 * @Repository beans, or attempt any real database connection. This makes
 * these tests fast and focused purely on "does this controller handle
 * HTTP requests/responses correctly," with the service layer entirely
 * replaced by a mock.
 *
 * @MockBean
 * ------------------------------------------------------------------------------
 * Tells Spring's test context: "register a Mockito mock of UrlService into
 * the application context in place of the real UrlServiceImpl bean."
 * UrlController's constructor asks for a UrlService, and Spring will inject
 * THIS mock — letting us fully control what the "service layer" returns
 * without it doing any real work, so we can test the controller's HTTP
 * translation logic (status codes, headers, JSON shape) in isolation.
 * ==============================================================================
 */
@WebMvcTest(UrlController.class)
class UrlControllerTest {

    @Autowired
    private MockMvc mockMvc;
    // MockMvc lets us PERFORM simulated HTTP requests (mockMvc.perform(...))
    // against the Spring MVC dispatcher in-memory — no real port/socket is
    // ever opened, making these tests fast and reliable in CI.

    @Autowired
    private ObjectMapper objectMapper;
    // Spring Boot auto-configures a shared Jackson ObjectMapper bean we can
    // reuse to serialize our request DTOs into JSON strings for the test
    // requests below.

    @MockBean
    private UrlService urlService;

    @Test
    void createShortUrl_returns201WithLocationHeader_whenUrlIsNew() throws Exception {
        CreateUrlRequest request = new CreateUrlRequest("https://www.example.com/path");
        CreateUrlResponse mockResponse = new CreateUrlResponse(
                "abc123",
                "http://localhost:8080/abc123",
                "https://www.example.com/path",
                LocalDateTime.now()
        );
        // alreadyExisted = false -> controller must return 201 Created
        when(urlService.createShortUrl(any(CreateUrlRequest.class)))
                .thenReturn(new CreateUrlResult(mockResponse, false));

        mockMvc.perform(post("/api/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())                                  // HTTP 201
                .andExpect(header().string("Location", "http://localhost:8080/abc123"))
                .andExpect(jsonPath("$.shortCode").value("abc123"))
                .andExpect(jsonPath("$.originalUrl").value("https://www.example.com/path"));
    }

    /**
     * THE DUPLICATE-URL FIX, verified at the HTTP layer: when the service
     * reports that this URL was already shortened before (alreadyExisted =
     * true), the controller must respond with 200 OK (not 201 Created) and
     * still return the EXISTING short code — proving a client posting the
     * same URL twice gets back the SAME shortUrl both times.
     */
    @Test
    void createShortUrl_returns200_whenUrlAlreadyExisted() throws Exception {
        CreateUrlRequest request = new CreateUrlRequest("https://www.example.com/path");
        CreateUrlResponse existingResponse = new CreateUrlResponse(
                "abc123",
                "http://localhost:8080/abc123",
                "https://www.example.com/path",
                LocalDateTime.now().minusDays(1)
        );
        when(urlService.createShortUrl(any(CreateUrlRequest.class)))
                .thenReturn(new CreateUrlResult(existingResponse, true));

        mockMvc.perform(post("/api/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())                                       // HTTP 200, not 201
                .andExpect(header().string("Location", "http://localhost:8080/abc123"))
                .andExpect(jsonPath("$.shortCode").value("abc123"));
    }

    @Test
    void createShortUrl_returns400_whenOriginalUrlIsBlank() throws Exception {
        // An intentionally invalid request (blank originalUrl) — should be
        // rejected by @Valid/@NotBlank BEFORE it ever reaches our mocked
        // urlService, proving validation runs at the web layer correctly.
        String invalidJson = "{\"originalUrl\": \"\"}";

        mockMvc.perform(post("/api/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidJson))
                .andExpect(status().isBadRequest())                                // HTTP 400
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.details").isArray());
    }

    @Test
    void redirect_returns302WithLocationHeader_whenShortCodeExists() throws Exception {
        when(urlService.resolveAndRecordClick(eq("abc123"), any(), any()))
                .thenReturn("https://www.example.com/path");

        mockMvc.perform(get("/abc123"))
                .andExpect(status().isFound())                                     // HTTP 302
                .andExpect(header().string("Location", "https://www.example.com/path"));
    }

    @Test
    void redirect_returns404_whenShortCodeDoesNotExist() throws Exception {
        when(urlService.resolveAndRecordClick(eq("missing"), any(), any()))
                .thenThrow(new UrlNotFoundException("missing"));

        mockMvc.perform(get("/missing"))
                .andExpect(status().isNotFound())                                  // HTTP 404
                .andExpect(jsonPath("$.status").value(404));
    }
}

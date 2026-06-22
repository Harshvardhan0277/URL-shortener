package com.example.urlshortener.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * ==============================================================================
 * WebConfig — Spring Web MVC configuration for static resources
 * ==============================================================================
 * This configuration explicitly tells Spring how to handle static resources
 * (HTML, CSS, JavaScript, images) and ensures they are served with the
 * correct cache headers and MIME types, while preventing the URL redirect
 * controller from intercepting static file requests.
 * ==============================================================================
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    /**
     * addResourceHandlers — Configure the serving of static resources
     * ==================================================================
     * Without this explicit configuration, Spring's default resource handling
     * might be overridden by our broad "/" @RequestMapping on UrlController.
     * By explicitly registering resource handlers, we ensure static files
     * (from classpath:static/) are served with appropriate HTTP cache headers
     * and before any controller mappings are attempted.
     *
     * Resource Handler Chain Execution Order:
     *   1. Request comes in (e.g., GET /css/styles.css)
     *   2. Spring first checks registered ResourceHandlers (this method)
     *   3. If matched, serves the resource and returns (stops here)
     *   4. If no match, proceeds to check @RequestMapping/@Controller routes
     *
     * By registering static resources here, we guarantee they match before
     * UrlController's "/{shortCode}" pattern can intercept them.
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // Handle all static resources with higher priority than controller mappings
        registry
                .addResourceHandler(
                        "/index.html",        // Explicitly handle the welcome page
                        "/css/**",            // All CSS files
                        "/js/**",             // All JavaScript files
                        "/app.js",            // Main app JavaScript
                        "/styles.css",        // Main styles
                        "/images/**",         // All image files
                        "/fonts/**",          // All font files
                        "/favicon.ico",       // Favicon
                        "/manifest.json"      // Web manifest for PWA
                )
                .addResourceLocations("classpath:/static/")
                .setCachePeriod(3600);        // Cache for 1 hour (in seconds)
                                               // Set to 0 to disable caching for development
                                               // Set to 31536000 for 1 year in production
    }
}

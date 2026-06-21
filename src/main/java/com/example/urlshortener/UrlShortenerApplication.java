package com.example.urlshortener;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * ==============================================================================
 * UrlShortenerApplication — the entry point of the entire Spring Boot app.
 * ==============================================================================
 * This is the class with the {@code public static void main(String[] args)}
 * method that the JVM actually invokes when you run
 * {@code java -jar url-shortener.jar}.
 *
 * WHAT HAPPENS WHEN main() RUNS:
 *   1. {@link SpringApplication#run} bootstraps the entire "Spring
 *      ApplicationContext" — Spring's internal registry of every object
 *      ("bean") it manages: our @RestControllers, @Service classes,
 *      @Repository interfaces, the embedded Tomcat server, the DataSource
 *      connecting to MySQL, etc.
 *   2. Spring performs "component scanning": starting from this class's
 *      package (com.example.urlshortener) and scanning every sub-package
 *      (controller, service, repository, ...), it finds every class
 *      annotated with @Component / @Service / @Repository / @RestController
 *      / @Configuration and registers them as beans.
 *   3. Spring Boot's "auto-configuration" inspects what's on the classpath
 *      (we have spring-boot-starter-web and spring-boot-starter-data-jpa)
 *      and automatically configures an embedded Tomcat server, a
 *      DispatcherServlet (the front controller all HTTP requests pass
 *      through), and a JPA EntityManagerFactory wired to our MySQL
 *      DataSource — all without us writing a single line of XML or manual
 *      wiring code.
 *
 * @SpringBootApplication
 * ------------------------------------------------------------------------------
 * This single annotation is shorthand ("meta-annotation") for THREE
 * annotations stacked together:
 *   - @Configuration   : marks this class itself as a source of bean
 *                        definitions (we don't define any inline here, but
 *                        the annotation enables the pattern).
 *   - @EnableAutoConfiguration : turns on Spring Boot's "guess sensible
 *                        defaults from the classpath" behavior described
 *                        above.
 *   - @ComponentScan   : tells Spring to scan this class's package (and
 *                        sub-packages) for other annotated classes to
 *                        register as beans. This is WHY our project
 *                        structure (controller/service/repository/... all
 *                        nested under com.example.urlshortener) matters —
 *                        if we put a class in a sibling package outside
 *                        com.example.urlshortener, Spring would never find
 *                        it without extra configuration.
 * ==============================================================================
 */
@SpringBootApplication
public class UrlShortenerApplication {

    /**
     * Standard Java entry point. We simply hand control over to Spring
     * Boot's {@link SpringApplication#run} method, passing:
     *   - this class (so Spring knows where to start component-scanning from)
     *   - the raw command-line arguments (e.g. --server.port=9090), which
     *     Spring Boot can use to override any application.yml property.
     */
    public static void main(String[] args) {
        SpringApplication.run(UrlShortenerApplication.class, args);
    }
}

package com.shortvideo.app;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base for integration tests that need the application and a database, and nothing else.
 *
 * <p>It carries what every such test repeated: the Spring Boot test setup, the database
 * ({@link TestDatabase}: Testcontainers by default, {@code TEST_POSTGRES_URL} for an existing
 * one), and a Kafka address that nothing listens on. Nothing in these tests publishes or consumes
 * (the relay is off under the "test" profile), so no broker is needed; listeners only retry their
 * connection in the background.
 *
 * <p>A subclass adds its own settings with a {@code @DynamicPropertySource} method of its own;
 * Spring runs that one too. Keep the name different from {@link #registerSharedProperties}, because
 * a static method with the same signature would hide this one.
 *
 * <p>Context sharing is the reason this is a base class rather than a helper each test calls. Spring
 * caches an application context under a key that includes the {@code @DynamicPropertySource}
 * methods, compared as {@code Method} objects. Every test whose only such method is the inherited
 * one has the same key, so they boot the application once between them. A test that adds a
 * property gets a context of its own.
 *
 * <p>Tests that share a context share its beans and the database, so a test must not leave state
 * behind that another could trip over.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
abstract class AbstractDatabaseIT {

    @DynamicPropertySource
    static void registerSharedProperties(DynamicPropertyRegistry registry) {
        TestDatabase.register(registry);
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:1");
    }
}

package com.shortvideo.app;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Points an integration test at a database.
 *
 * <p>Testcontainers is the default and stays the default: a disposable database
 * per run is what makes these tests trustworthy. But it requires the Docker
 * <em>API</em>, and that is not always reachable even where Docker is — a broken
 * Docker Desktop can answer the CLI while returning errors to the daemon socket,
 * and CI commonly provides a database as a service container with no Docker socket
 * inside the job at all. In both cases the tests are unrunnable for reasons that
 * have nothing to do with the code.
 *
 * <p>Setting {@code TEST_POSTGRES_URL} uses that database instead. The container
 * is then never started — the static initialiser is what would pull the image, so
 * it is created lazily rather than as a field initialiser.
 *
 * <pre>
 * TEST_POSTGRES_URL=jdbc:postgresql://localhost:5432/short_video_test \
 * TEST_POSTGRES_USER=short_video_app TEST_POSTGRES_PASSWORD=short_video_app \
 *   mvn -pl backend/app test
 * </pre>
 *
 * <p>Point it at a scratch database, not a working one: Flyway migrates whatever
 * it is given, and the tests write freely.
 */
final class TestDatabase {

    private static final String URL_ENV = "TEST_POSTGRES_URL";

    private static PostgreSQLContainer<?> container;

    static boolean usesExternalDatabase() {
        String url = System.getenv(URL_ENV);
        return url != null && !url.isBlank();
    }

    /**
     * Registers datasource properties, starting a container only when no external
     * database was supplied.
     */
    static void register(DynamicPropertyRegistry registry) {
        if (usesExternalDatabase()) {
            registry.add("spring.datasource.url", () -> System.getenv(URL_ENV));
            registry.add("spring.datasource.username", () -> envOrDefault("TEST_POSTGRES_USER", "short_video_app"));
            registry.add("spring.datasource.password", () -> envOrDefault("TEST_POSTGRES_PASSWORD", "short_video_app"));
            return;
        }
        registry.add("spring.datasource.url", () -> container().getJdbcUrl());
        registry.add("spring.datasource.username", () -> container().getUsername());
        registry.add("spring.datasource.password", () -> container().getPassword());
    }

    @SuppressWarnings("resource")
    private static synchronized PostgreSQLContainer<?> container() {
        if (container == null) {
            container = new PostgreSQLContainer<>("postgres:17.4-alpine")
                    .withDatabaseName("short_video")
                    .withUsername("short_video_app")
                    .withPassword("short_video_app");
            container.start();
        }
        return container;
    }

    private static String envOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private TestDatabase() {}
}

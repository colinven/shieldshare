package net.shieldshare.shieldshare.support;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Base class for tests that need a real Postgres.
 * <p>
 * Dev gets its database from Docker Compose, but Boot skips Compose during tests
 * (spring.docker.compose.skip.in-tests defaults to true), and application.yaml intentionally has no
 * spring.datasource block. Testcontainers is how tests get a database at all.
 * <p>
 * The container is static and lives on the shared base class, so one container is started for the
 * whole test run rather than one per test class. {@code @ServiceConnection} points the application's
 * DataSource at it, which also keeps tests away from the dev database on localhost:5432.
 * <p>
 * The image must match compose.yaml. The contention test in {@code SecretsJdbcRepositoryIT} depends
 * on Postgres' READ COMMITTED locking behavior, so testing a different major than dev would weaken
 * it. Change both or neither.
 */
@Testcontainers
public abstract class AbstractPostgresIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");
}

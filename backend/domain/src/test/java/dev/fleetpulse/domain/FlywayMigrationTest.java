package dev.fleetpulse.domain;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class FlywayMigrationTest {

    // Same recipe docker-compose.yml builds for the "postgis" service: keep the
    // Dockerfile as the single source of truth instead of duplicating it here.
    private static final Path POSTGIS_PARTMAN_DOCKERFILE = Path
        .of(System.getProperty("user.dir"), "..", "..", "docker", "postgis-partman", "Dockerfile")
        .normalize();

    @Container
    static final PostgreSQLContainer postgis = new PostgreSQLContainer(
        DockerImageName
            .parse(
                new ImageFromDockerfile("fleetpulse/postgis-partman:test", false)
                    .withDockerfile(POSTGIS_PARTMAN_DOCKERFILE)
                    .get()
            )
            .asCompatibleSubstituteFor("postgres")
    );

    @Test
    void migrationInstallsPostgisAndPgPartmanExtensions() throws Exception {
        Flyway
            .configure()
            .dataSource(postgis.getJdbcUrl(), postgis.getUsername(), postgis.getPassword())
            .load()
            .migrate();

        List<String> installedExtensions = new ArrayList<>();
        try (
            Connection connection = DriverManager
                .getConnection(postgis.getJdbcUrl(), postgis.getUsername(), postgis.getPassword());
            Statement statement = connection.createStatement();
            ResultSet resultSet = statement
                .executeQuery("SELECT extname FROM pg_extension WHERE extname IN ('postgis', 'pg_partman') ORDER BY extname")
        ) {
            while (resultSet.next()) {
                installedExtensions.add(resultSet.getString("extname"));
            }
        }

        assertThat(installedExtensions).containsExactly("pg_partman", "postgis");
    }

    @Test
    void migrationIsIdempotentWhenAppliedTwice() {
        Flyway flyway = Flyway
            .configure()
            .dataSource(postgis.getJdbcUrl(), postgis.getUsername(), postgis.getPassword())
            .load();

        flyway.migrate();
        MigrateResult secondRun = flyway.migrate();

        assertThat(secondRun.migrationsExecuted).isZero();
    }
}

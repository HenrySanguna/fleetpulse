package dev.fleetpulse.processor.partition;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

// Task 1.5: proves the Java wiring around partman.run_maintenance_proc()
// (design.md) actually reaches a real pg_partman-enabled Postgres -- the
// same Dockerfile image domain's schema tests and the real deployment use
// (docker/postgis-partman/Dockerfile) -- rather than asserting against a
// mocked JdbcTemplate, since the whole point of this component is that the
// SQL call it issues is real.
@Testcontainers
class PartitionMaintenanceTaskTest {

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
    void maintainPartitionsInvokesRunMaintenanceProcWithoutShrinkingThePartitionHorizon() throws Exception {
        Flyway
            .configure()
            .dataSource(postgis.getJdbcUrl(), postgis.getUsername(), postgis.getPassword())
            .load()
            .migrate();

        PartitionMaintenanceTask task = new PartitionMaintenanceTask(newJdbcTemplate());

        Instant beforeMaintenance = furthestPartitionLowerBound();

        task.maintainPartitions();

        Instant afterMaintenance = furthestPartitionLowerBound();
        assertThat(afterMaintenance).isAfterOrEqualTo(beforeMaintenance);
    }

    private static JdbcTemplate newJdbcTemplate() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
            postgis.getJdbcUrl(), postgis.getUsername(), postgis.getPassword()
        );
        return new JdbcTemplate(dataSource);
    }

    private static Instant furthestPartitionLowerBound() throws SQLException {
        try (
            Connection connection = DriverManager
                .getConnection(postgis.getJdbcUrl(), postgis.getUsername(), postgis.getPassword());
            PreparedStatement statement = connection.prepareStatement(
                "SELECT max((split_part(pg_get_expr(c.relpartbound, c.oid), '''', 2))::timestamptz) AS furthest_start "
                    + "FROM pg_inherits i "
                    + "JOIN pg_class c ON c.oid = i.inhrelid "
                    + "JOIN pg_class p ON p.oid = i.inhparent "
                    + "WHERE p.relname = 'positions' "
                    + "AND pg_get_expr(c.relpartbound, c.oid) LIKE 'FOR VALUES FROM%'"
            );
            ResultSet resultSet = statement.executeQuery()
        ) {
            assertThat(resultSet.next()).isTrue();
            Timestamp furthest = resultSet.getTimestamp("furthest_start");
            assertThat(furthest).withFailMessage("No ranged positions partitions found").isNotNull();
            return furthest.toInstant();
        }
    }
}

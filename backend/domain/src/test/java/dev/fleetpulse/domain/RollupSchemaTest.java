package dev.fleetpulse.domain;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Pure JDBC + Flyway, no Spring context, same recipe as TripSchemaTest:
// proves the vehicle_hourly/vehicle_daily schema (V13) itself -- the
// recompute algorithm/ON CONFLICT DO UPDATE idempotency story is
// HourlyRollupAggregatorTest/VehicleRollupEndToEndTest's own job, processor
// module (tests 5.4/5.5).
@Testcontainers
class RollupSchemaTest {

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
    void vehicleHourlyHasCompositePrimaryKeyOnVehicleIdAndHour() throws Exception {
        migrate();

        try (Connection connection = connect()) {
            UUID organizationId = insertOrganization(connection, "Acme Rollup Schema Org 1");
            UUID vehicleId = insertVehicle(connection, organizationId, "Truck-RS-1");
            Instant hour = Instant.now().minusSeconds(7200);

            insertHourlyRow(connection, organizationId, vehicleId, hour, 62.0f);

            assertThatThrownBy(() -> insertHourlyRow(connection, organizationId, vehicleId, hour, 70.0f))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("vehicle_hourly_pkey");
        }
    }

    @Test
    void vehicleHourlyAcceptsANullMaxSpeedKmh() throws Exception {
        migrate();

        try (Connection connection = connect()) {
            UUID organizationId = insertOrganization(connection, "Acme Rollup Schema Org 2");
            UUID vehicleId = insertVehicle(connection, organizationId, "Truck-RS-2");
            Instant hour = Instant.now().minusSeconds(7200);

            insertHourlyRow(connection, organizationId, vehicleId, hour, null);

            try (
                PreparedStatement statement = connection
                    .prepareStatement("SELECT max_speed_kmh FROM vehicle_hourly WHERE vehicle_id = ? AND hour = ?")
            ) {
                statement.setObject(1, vehicleId);
                statement.setTimestamp(2, Timestamp.from(hour));
                try (ResultSet resultSet = statement.executeQuery()) {
                    assertThat(resultSet.next()).isTrue();
                    resultSet.getFloat("max_speed_kmh");
                    assertThat(resultSet.wasNull()).isTrue();
                }
            }
        }
    }

    @Test
    void vehicleHourlyAcceptsAWellFormedRowWithAllMetrics() throws Exception {
        migrate();

        try (Connection connection = connect()) {
            UUID organizationId = insertOrganization(connection, "Acme Rollup Schema Org 3");
            UUID vehicleId = insertVehicle(connection, organizationId, "Truck-RS-3");
            Instant hour = Instant.now().minusSeconds(7200);

            insertHourlyRow(connection, organizationId, vehicleId, hour, 55.0f);

            try (
                PreparedStatement statement = connection.prepareStatement(
                    "SELECT distance_km, moving_secs, idle_secs, max_speed_kmh FROM vehicle_hourly WHERE vehicle_id = ? AND hour = ?"
                )
            ) {
                statement.setObject(1, vehicleId);
                statement.setTimestamp(2, Timestamp.from(hour));
                try (ResultSet resultSet = statement.executeQuery()) {
                    assertThat(resultSet.next()).isTrue();
                    assertThat(resultSet.getFloat("distance_km")).isEqualTo(8.4f);
                    assertThat(resultSet.getInt("moving_secs")).isEqualTo(1800);
                    assertThat(resultSet.getInt("idle_secs")).isEqualTo(300);
                    assertThat(resultSet.getFloat("max_speed_kmh")).isEqualTo(55.0f);
                }
            }
        }
    }

    @Test
    void vehicleHourlyRejectsAnUnknownVehicleId() throws Exception {
        migrate();

        try (Connection connection = connect()) {
            UUID organizationId = insertOrganization(connection, "Acme Rollup Schema Org 4");
            Instant hour = Instant.now().minusSeconds(7200);

            assertThatThrownBy(() -> insertHourlyRow(connection, organizationId, UUID.randomUUID(), hour, 40.0f))
                .isInstanceOf(SQLException.class);
        }
    }

    @Test
    void vehicleDailyHasCompositePrimaryKeyOnVehicleIdAndDay() throws Exception {
        migrate();

        try (Connection connection = connect()) {
            UUID organizationId = insertOrganization(connection, "Acme Rollup Schema Org 5");
            UUID vehicleId = insertVehicle(connection, organizationId, "Truck-RS-5");
            LocalDate day = LocalDate.now().minusDays(1);

            insertDailyRow(connection, organizationId, vehicleId, day, 90.0f);

            assertThatThrownBy(() -> insertDailyRow(connection, organizationId, vehicleId, day, 100.0f))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("vehicle_daily_pkey");
        }
    }

    @Test
    void vehicleDailyAcceptsAWellFormedRowWithAllMetrics() throws Exception {
        migrate();

        try (Connection connection = connect()) {
            UUID organizationId = insertOrganization(connection, "Acme Rollup Schema Org 6");
            UUID vehicleId = insertVehicle(connection, organizationId, "Truck-RS-6");
            LocalDate day = LocalDate.now().minusDays(1);

            insertDailyRow(connection, organizationId, vehicleId, day, 88.0f);

            try (
                PreparedStatement statement = connection.prepareStatement(
                    "SELECT distance_km, moving_secs, idle_secs, max_speed_kmh FROM vehicle_daily WHERE vehicle_id = ? AND day = ?"
                )
            ) {
                statement.setObject(1, vehicleId);
                statement.setDate(2, Date.valueOf(day));
                try (ResultSet resultSet = statement.executeQuery()) {
                    assertThat(resultSet.next()).isTrue();
                    assertThat(resultSet.getFloat("distance_km")).isEqualTo(120.5f);
                    assertThat(resultSet.getInt("moving_secs")).isEqualTo(28800);
                    assertThat(resultSet.getInt("idle_secs")).isEqualTo(3600);
                    assertThat(resultSet.getFloat("max_speed_kmh")).isEqualTo(88.0f);
                }
            }
        }
    }

    private static void insertHourlyRow(
        Connection connection, UUID organizationId, UUID vehicleId, Instant hour, Float maxSpeedKmh
    ) throws SQLException {
        try (
            PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO vehicle_hourly (vehicle_id, organization_id, hour, distance_km, moving_secs, idle_secs, max_speed_kmh, updated_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
            )
        ) {
            statement.setObject(1, vehicleId);
            statement.setObject(2, organizationId);
            statement.setTimestamp(3, Timestamp.from(hour));
            statement.setFloat(4, 8.4f);
            statement.setInt(5, 1800);
            statement.setInt(6, 300);
            if (maxSpeedKmh == null) {
                statement.setNull(7, java.sql.Types.REAL);
            } else {
                statement.setFloat(7, maxSpeedKmh);
            }
            statement.setTimestamp(8, Timestamp.from(Instant.now()));
            statement.executeUpdate();
        }
    }

    private static void insertDailyRow(
        Connection connection, UUID organizationId, UUID vehicleId, LocalDate day, Float maxSpeedKmh
    ) throws SQLException {
        try (
            PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO vehicle_daily (vehicle_id, organization_id, day, distance_km, moving_secs, idle_secs, max_speed_kmh, updated_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
            )
        ) {
            statement.setObject(1, vehicleId);
            statement.setObject(2, organizationId);
            statement.setDate(3, Date.valueOf(day));
            statement.setFloat(4, 120.5f);
            statement.setInt(5, 28800);
            statement.setInt(6, 3600);
            if (maxSpeedKmh == null) {
                statement.setNull(7, java.sql.Types.REAL);
            } else {
                statement.setFloat(7, maxSpeedKmh);
            }
            statement.setTimestamp(8, Timestamp.from(Instant.now()));
            statement.executeUpdate();
        }
    }

    private static void migrate() {
        Flyway
            .configure()
            .dataSource(postgis.getJdbcUrl(), postgis.getUsername(), postgis.getPassword())
            .load()
            .migrate();
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(postgis.getJdbcUrl(), postgis.getUsername(), postgis.getPassword());
    }

    private static UUID insertOrganization(Connection connection, String name) throws SQLException {
        UUID id = UUID.randomUUID();
        try (
            PreparedStatement statement = connection
                .prepareStatement("INSERT INTO organizations (id, name, created_at) VALUES (?, ?, ?)")
        ) {
            statement.setObject(1, id);
            statement.setString(2, name);
            statement.setTimestamp(3, Timestamp.from(Instant.now()));
            statement.executeUpdate();
        }
        return id;
    }

    private static UUID insertVehicle(Connection connection, UUID organizationId, String label) throws SQLException {
        UUID id = UUID.randomUUID();
        try (
            PreparedStatement statement = connection
                .prepareStatement("INSERT INTO vehicles (id, organization_id, label, created_at) VALUES (?, ?, ?, ?)")
        ) {
            statement.setObject(1, id);
            statement.setObject(2, organizationId);
            statement.setString(3, label);
            statement.setTimestamp(4, Timestamp.from(Instant.now()));
            statement.executeUpdate();
        }
        return id;
    }
}

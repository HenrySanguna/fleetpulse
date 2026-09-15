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
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Pure JDBC + Flyway, no Spring context, same recipe as GeofenceSchemaTest
// and TelemetrySchemaTest: proves the trips schema + organizations'
// trip_stop_threshold_secs column (V10) itself -- this work unit is
// schema-only, no segmentation algorithm wiring exists to exercise instead
// (that is TripSegmenterTest/TripSegmentationEndToEndTest's job, processor
// module).
@Testcontainers
class TripSchemaTest {

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
    void organizationsDefaultsTripStopThresholdSecsToFiveMinutes() throws Exception {
        migrate();

        try (Connection connection = connect()) {
            UUID organizationId = insertOrganization(connection, "Acme Trips Org 1");

            try (
                PreparedStatement statement = connection
                    .prepareStatement("SELECT trip_stop_threshold_secs FROM organizations WHERE id = ?")
            ) {
                statement.setObject(1, organizationId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    assertThat(resultSet.next()).isTrue();
                    assertThat(resultSet.getInt("trip_stop_threshold_secs")).isEqualTo(300);
                }
            }
        }
    }

    @Test
    void organizationsRejectsNonPositiveTripStopThresholdSecs() throws Exception {
        migrate();

        try (Connection connection = connect()) {
            UUID organizationId = insertOrganization(connection, "Acme Trips Org 2");

            assertThatThrownBy(() -> updateTripStopThresholdSecs(connection, organizationId, 0))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("chk_organizations_trip_stop_threshold_secs");
        }
    }

    @Test
    void tripsHasUniqueConstraintOnVehicleIdAndStartedAt() throws Exception {
        migrate();

        try (Connection connection = connect()) {
            UUID organizationId = insertOrganization(connection, "Acme Trips Org 3");
            UUID vehicleId = insertVehicle(connection, organizationId, "Truck-TR-1");
            Instant startedAt = Instant.now().minusSeconds(3600);
            Instant endedAt = startedAt.plusSeconds(600);

            insertTrip(connection, organizationId, vehicleId, startedAt, endedAt);

            assertThatThrownBy(() -> insertTrip(connection, organizationId, vehicleId, startedAt, endedAt.plusSeconds(60)))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("uq_trips_vehicle_started_at");
        }
    }

    @Test
    void tripsRejectsEndedAtBeforeStartedAt() throws Exception {
        migrate();

        try (Connection connection = connect()) {
            UUID organizationId = insertOrganization(connection, "Acme Trips Org 4");
            UUID vehicleId = insertVehicle(connection, organizationId, "Truck-TR-2");
            Instant startedAt = Instant.now().minusSeconds(3600);

            assertThatThrownBy(() -> insertTrip(connection, organizationId, vehicleId, startedAt, startedAt.minusSeconds(1)))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("chk_trips_ended_after_started");
        }
    }

    @Test
    void tripsAcceptsAWellFormedRowWithAllMetrics() throws Exception {
        migrate();

        try (Connection connection = connect()) {
            UUID organizationId = insertOrganization(connection, "Acme Trips Org 5");
            UUID vehicleId = insertVehicle(connection, organizationId, "Truck-TR-3");
            Instant startedAt = Instant.now().minusSeconds(3600);
            Instant endedAt = startedAt.plusSeconds(900);
            UUID tripId = insertTrip(connection, organizationId, vehicleId, startedAt, endedAt);

            try (
                PreparedStatement statement = connection.prepareStatement(
                    "SELECT distance_km, duration_secs, idle_secs, max_speed_kmh, avg_speed_kmh FROM trips WHERE id = ?"
                )
            ) {
                statement.setObject(1, tripId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    assertThat(resultSet.next()).isTrue();
                    assertThat(resultSet.getFloat("distance_km")).isEqualTo(12.5f);
                    assertThat(resultSet.getInt("duration_secs")).isEqualTo(900);
                    assertThat(resultSet.getInt("idle_secs")).isEqualTo(30);
                    assertThat(resultSet.getFloat("max_speed_kmh")).isEqualTo(62.0f);
                    assertThat(resultSet.getFloat("avg_speed_kmh")).isEqualTo(50.0f);
                }
            }
        }
    }

    private static void updateTripStopThresholdSecs(Connection connection, UUID organizationId, int value) throws SQLException {
        try (
            PreparedStatement statement = connection
                .prepareStatement("UPDATE organizations SET trip_stop_threshold_secs = ? WHERE id = ?")
        ) {
            statement.setInt(1, value);
            statement.setObject(2, organizationId);
            statement.executeUpdate();
        }
    }

    private static UUID insertTrip(
        Connection connection, UUID organizationId, UUID vehicleId, Instant startedAt, Instant endedAt
    ) throws SQLException {
        UUID id = UUID.randomUUID();
        try (
            PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO trips (id, organization_id, vehicle_id, started_at, ended_at, "
                    + "distance_km, duration_secs, idle_secs, max_speed_kmh, avg_speed_kmh, created_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
            )
        ) {
            statement.setObject(1, id);
            statement.setObject(2, organizationId);
            statement.setObject(3, vehicleId);
            statement.setTimestamp(4, Timestamp.from(startedAt));
            statement.setTimestamp(5, Timestamp.from(endedAt));
            statement.setFloat(6, 12.5f);
            statement.setInt(7, 900);
            statement.setInt(8, 30);
            statement.setFloat(9, 62.0f);
            statement.setFloat(10, 50.0f);
            statement.setTimestamp(11, Timestamp.from(Instant.now()));
            statement.executeUpdate();
        }
        return id;
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

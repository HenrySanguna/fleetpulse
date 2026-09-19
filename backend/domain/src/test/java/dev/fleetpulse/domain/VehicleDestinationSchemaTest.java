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

// Task 2.1 (WU2, V11): pure JDBC + Flyway, no Spring context, same recipe as
// TripSchemaTest/GeofenceSchemaTest -- proves the vehicle_destinations
// schema itself (one row per vehicle, the eta_* CHECK constraints, and the
// "assigning a new destination replaces the previous row" upsert semantics).
// The calculation itself is SinuosityEtaCalculatorTest's job (pure) and the
// live wiring is EtaEndToEndTest's job (processor module).
@Testcontainers
class VehicleDestinationSchemaTest {

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
    void assigningANewDestinationReplacesThePreviousRowForTheSameVehicle() throws Exception {
        migrate();

        try (Connection connection = connect()) {
            UUID organizationId = insertOrganization(connection, "Acme Eta Org 1");
            UUID vehicleId = insertVehicle(connection, organizationId, "Truck-ETA-1");
            Instant firstAssignedAt = Instant.now().minusSeconds(3600);

            insertDestination(connection, vehicleId, organizationId, 4.70, -74.05, firstAssignedAt);
            upsertDestination(connection, vehicleId, organizationId, 4.80, -74.10, Instant.now());

            try (
                PreparedStatement statement = connection
                    .prepareStatement("SELECT count(*) FROM vehicle_destinations WHERE vehicle_id = ?")
            ) {
                statement.setObject(1, vehicleId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    assertThat(resultSet.next()).isTrue();
                    assertThat(resultSet.getInt(1)).isEqualTo(1);
                }
            }
            try (
                PreparedStatement statement = connection.prepareStatement(
                    "SELECT ST_Y(destination::geometry) AS lat, ST_X(destination::geometry) AS lon "
                        + "FROM vehicle_destinations WHERE vehicle_id = ?"
                )
            ) {
                statement.setObject(1, vehicleId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    assertThat(resultSet.next()).isTrue();
                    assertThat(resultSet.getDouble("lat")).isEqualTo(4.80);
                    assertThat(resultSet.getDouble("lon")).isEqualTo(-74.10);
                }
            }
        }
    }

    @Test
    void rejectsANegativeEtaSeconds() throws Exception {
        migrate();

        try (Connection connection = connect()) {
            UUID organizationId = insertOrganization(connection, "Acme Eta Org 2");
            UUID vehicleId = insertVehicle(connection, organizationId, "Truck-ETA-2");
            insertDestination(connection, vehicleId, organizationId, 4.70, -74.05, Instant.now());

            assertThatThrownBy(() -> updateEta(connection, vehicleId, -1, 30))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("chk_vehicle_destinations_eta_seconds");
        }
    }

    @Test
    void rejectsANegativeEtaMarginSeconds() throws Exception {
        migrate();

        try (Connection connection = connect()) {
            UUID organizationId = insertOrganization(connection, "Acme Eta Org 3");
            UUID vehicleId = insertVehicle(connection, organizationId, "Truck-ETA-3");
            insertDestination(connection, vehicleId, organizationId, 4.70, -74.05, Instant.now());

            assertThatThrownBy(() -> updateEta(connection, vehicleId, 600, -1))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("chk_vehicle_destinations_eta_margin_seconds");
        }
    }

    private static void insertDestination(
        Connection connection, UUID vehicleId, UUID organizationId, double lat, double lon, Instant assignedAt
    ) throws SQLException {
        try (
            PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO vehicle_destinations (vehicle_id, organization_id, destination, assigned_at) "
                    + "VALUES (?, ?, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography, ?)"
            )
        ) {
            statement.setObject(1, vehicleId);
            statement.setObject(2, organizationId);
            statement.setDouble(3, lon);
            statement.setDouble(4, lat);
            statement.setTimestamp(5, Timestamp.from(assignedAt));
            statement.executeUpdate();
        }
    }

    private static void upsertDestination(
        Connection connection, UUID vehicleId, UUID organizationId, double lat, double lon, Instant assignedAt
    ) throws SQLException {
        try (
            PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO vehicle_destinations (vehicle_id, organization_id, destination, assigned_at) "
                    + "VALUES (?, ?, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography, ?) "
                    + "ON CONFLICT (vehicle_id) DO UPDATE SET organization_id = excluded.organization_id, "
                    + "destination = excluded.destination, assigned_at = excluded.assigned_at, "
                    + "eta_seconds = NULL, eta_margin_seconds = NULL, eta_calculated_at = NULL"
            )
        ) {
            statement.setObject(1, vehicleId);
            statement.setObject(2, organizationId);
            statement.setDouble(3, lon);
            statement.setDouble(4, lat);
            statement.setTimestamp(5, Timestamp.from(assignedAt));
            statement.executeUpdate();
        }
    }

    private static void updateEta(Connection connection, UUID vehicleId, int etaSeconds, int marginSeconds) throws SQLException {
        try (
            PreparedStatement statement = connection.prepareStatement(
                "UPDATE vehicle_destinations SET eta_seconds = ?, eta_margin_seconds = ?, eta_calculated_at = ? "
                    + "WHERE vehicle_id = ?"
            )
        ) {
            statement.setInt(1, etaSeconds);
            statement.setInt(2, marginSeconds);
            statement.setTimestamp(3, Timestamp.from(Instant.now()));
            statement.setObject(4, vehicleId);
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

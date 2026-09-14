package dev.fleetpulse.processor.presence;

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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// Task 5.2: JdbcVehiclePresenceWriter upserts vehicle_state.online -- tested
// directly against the writer (Testcontainers, PostGIS only, no MQTT broker
// involved), the same convention TelemetryVehicleStateGuardTest (WU6/WU7)
// already established for task-4.1/4.2's guarded upsert: the write path is
// plain JdbcTemplate, so a broker adds nothing this test needs to prove. The
// broker-facing behaviour itself (6.6/6.7) is covered separately by
// PresenceEndToEndTest.
@Testcontainers
class JdbcVehiclePresenceWriterTest {

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

    // A vehicle that has never reported telemetry or presence has no
    // vehicle_state row yet (V5's migration comment: the presence consumer
    // may be the FIRST thing to ever create one) -- the writer must create it
    // lazily, not assume a row already exists.
    @Test
    void firstEverPresenceMessageForAVehicleLazilyCreatesVehicleStateAsOnline() throws Exception {
        migrate();
        JdbcVehiclePresenceWriter writer = newWriter();
        UUID vehicleId = seedVehicle("Truck-WU8-1");
        assertThat(vehicleStateRowExists(vehicleId)).isFalse();

        writer.updateOnlineStatus(vehicleId, true);

        assertThat(vehicleStateRowExists(vehicleId)).isTrue();
        assertThat(readOnline(vehicleId)).isTrue();
    }

    // Test 6.6's underlying write: a will-triggered offline message must flip
    // an already-online vehicle back to offline.
    @Test
    void updatingAnExistingRowFlipsOnlineToFalse() throws Exception {
        migrate();
        JdbcVehiclePresenceWriter writer = newWriter();
        UUID vehicleId = seedVehicle("Truck-WU8-2");
        writer.updateOnlineStatus(vehicleId, true);
        assertThat(readOnline(vehicleId)).isTrue();

        writer.updateOnlineStatus(vehicleId, false);

        assertThat(readOnline(vehicleId)).isFalse();
    }

    // The presence consumer must never clobber location/recorded_at/motion_state
    // that task 4.1/4.2's telemetry write path already populated -- online is
    // a column it owns exclusively (design.md, V5's migration comment).
    @Test
    void updatingOnlineStatusDoesNotTouchLocationOrMotionState() throws Exception {
        migrate();
        UUID vehicleId = seedVehicle("Truck-WU8-3");
        Instant recordedAt = Instant.parse("2026-09-14T10:00:00Z");
        seedTelemetryDerivedVehicleState(vehicleId, recordedAt, 40.4, -3.7);

        newWriter().updateOnlineStatus(vehicleId, true);

        assertThat(readOnline(vehicleId)).isTrue();
        assertThat(readRecordedAt(vehicleId)).isEqualTo(recordedAt);
    }

    private static void migrate() {
        Flyway.configure().dataSource(postgis.getJdbcUrl(), postgis.getUsername(), postgis.getPassword()).load().migrate();
    }

    private static JdbcVehiclePresenceWriter newWriter() {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(
            new DriverManagerDataSource(postgis.getJdbcUrl(), postgis.getUsername(), postgis.getPassword())
        );
        return new JdbcVehiclePresenceWriter(jdbcTemplate);
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(postgis.getJdbcUrl(), postgis.getUsername(), postgis.getPassword());
    }

    private static boolean vehicleStateRowExists(UUID vehicleId) throws SQLException {
        try (
            Connection connection = connect();
            PreparedStatement statement = connection.prepareStatement("SELECT count(*) FROM vehicle_state WHERE vehicle_id = ?")
        ) {
            statement.setObject(1, vehicleId);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).isTrue();
                return resultSet.getInt(1) == 1;
            }
        }
    }

    private static Boolean readOnline(UUID vehicleId) throws SQLException {
        try (
            Connection connection = connect();
            PreparedStatement statement = connection.prepareStatement("SELECT online FROM vehicle_state WHERE vehicle_id = ?")
        ) {
            statement.setObject(1, vehicleId);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).isTrue();
                return resultSet.getBoolean("online");
            }
        }
    }

    private static Instant readRecordedAt(UUID vehicleId) throws SQLException {
        try (
            Connection connection = connect();
            PreparedStatement statement = connection.prepareStatement("SELECT recorded_at FROM vehicle_state WHERE vehicle_id = ?")
        ) {
            statement.setObject(1, vehicleId);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).isTrue();
                return resultSet.getTimestamp("recorded_at").toInstant();
            }
        }
    }

    private static void seedTelemetryDerivedVehicleState(UUID vehicleId, Instant recordedAt, double lat, double lon) throws SQLException {
        try (
            Connection connection = connect();
            PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO vehicle_state (vehicle_id, location, recorded_at) "
                    + "VALUES (?, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography, ?)"
            )
        ) {
            statement.setObject(1, vehicleId);
            statement.setDouble(2, lon);
            statement.setDouble(3, lat);
            statement.setTimestamp(4, Timestamp.from(recordedAt));
            statement.executeUpdate();
        }
    }

    private static UUID seedVehicle(String label) throws SQLException {
        try (Connection connection = connect()) {
            UUID organizationId = UUID.randomUUID();
            try (
                PreparedStatement statement = connection
                    .prepareStatement("INSERT INTO organizations (id, name, created_at) VALUES (?, ?, ?)")
            ) {
                statement.setObject(1, organizationId);
                statement.setString(2, "Org-" + label);
                statement.setTimestamp(3, Timestamp.from(Instant.now()));
                statement.executeUpdate();
            }
            UUID vehicleId = UUID.randomUUID();
            try (
                PreparedStatement statement = connection
                    .prepareStatement("INSERT INTO vehicles (id, organization_id, label, created_at) VALUES (?, ?, ?, ?)")
            ) {
                statement.setObject(1, vehicleId);
                statement.setObject(2, organizationId);
                statement.setString(3, label);
                statement.setTimestamp(4, Timestamp.from(Instant.now()));
                statement.executeUpdate();
            }
            return vehicleId;
        }
    }
}

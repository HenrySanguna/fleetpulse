package dev.fleetpulse.processor.telemetry;

import dev.fleetpulse.geocore.MotionState;
import dev.fleetpulse.processor.config.FleetpulseMotionDetectionProperties;
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
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// Task 4.1: `JdbcTelemetryPositionWriter` (WU4/WU5's existing positions
// writer) also upserts `vehicle_state` for every message it persists, but
// ONLY advances it when the incoming recordedAt is newer than what is
// currently stored -- the monotonic guard from design.md
// ("Desorden: el estado actual no se puede sobrescribir a ciegas"). Tested
// directly against the writer, the same way TelemetryBatchWriteTest (WU4)
// proves 6.5/6.10 without a live MQTT broker: the guard lives inside the
// same JdbcTemplate-only write path as task 3.2, so a broker adds nothing
// this test needs to prove.
//
// Lazy-upsert-vs-pre-seeded resolution (task 4.1): vehicle_state rows are
// lazily upserted the first time a vehicle is ever seen by this writer, via
// a single `INSERT ... ON CONFLICT (vehicle_id) DO UPDATE ... WHERE`
// statement -- not pre-seeded by a separate process. This also covers the
// case where a row already exists with location/recorded_at still NULL
// (V5's migration comment: the future presence consumer, WU8, may create a
// vehicle_state row from a testament before any telemetry ever arrives) --
// the guard's WHERE clause treats a NULL stored recorded_at as "older than
// anything", so the very first telemetry message always wins regardless of
// which side created the row first.
@Testcontainers
class TelemetryVehicleStateGuardTest {

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

    // Test 6.2: an old message arriving after a recent one must NOT regress
    // `vehicle_state` -- it must keep reflecting the recent position.
    @Test
    void staleMessageAfterARecentOneDoesNotRegressVehicleState() throws Exception {
        migrate();
        JdbcTelemetryPositionWriter writer = newWriter();
        UUID vehicleId = seedVehicle("Truck-WU6-1");
        Instant recent = Instant.parse("2026-09-11T10:00:00Z");
        Instant stale = Instant.parse("2026-09-11T09:30:00Z");

        writer.writeBatch(List.of(telemetry(vehicleId, recent, 40.40, -3.70)));
        writer.writeBatch(List.of(telemetry(vehicleId, stale, 40.10, -3.90)));

        VehicleState state = readVehicleState(vehicleId);
        assertThat(state.recordedAt()).isEqualTo(recent);
        assertThat(state.lat()).isEqualTo(40.40);
        assertThat(state.lon()).isEqualTo(-3.70);
    }

    // Test 6.3: the same stale message from 6.2 must still land in
    // `positions` as its own row -- only `vehicle_state` has a monotonic
    // guard, `positions` (the append-only history) has none.
    @Test
    void staleMessageAfterARecentOneIsStillPersistedInPositions() throws Exception {
        migrate();
        JdbcTelemetryPositionWriter writer = newWriter();
        UUID vehicleId = seedVehicle("Truck-WU6-2");
        Instant recent = Instant.parse("2026-09-11T10:00:00Z");
        Instant stale = Instant.parse("2026-09-11T09:30:00Z");

        writer.writeBatch(List.of(telemetry(vehicleId, recent, 40.40, -3.70)));
        writer.writeBatch(List.of(telemetry(vehicleId, stale, 40.10, -3.90)));

        assertThat(countPositions(vehicleId)).isEqualTo(2);
        assertThat(positionExists(vehicleId, stale)).isTrue();
        assertThat(positionExists(vehicleId, recent)).isTrue();
    }

    // Companion case: a newer message arriving after an older one DOES
    // advance vehicle_state -- proves the guard is a genuine `<` comparison,
    // not simply "never update".
    @Test
    void newerMessageAfterAnOlderOneAdvancesVehicleState() throws Exception {
        migrate();
        JdbcTelemetryPositionWriter writer = newWriter();
        UUID vehicleId = seedVehicle("Truck-WU6-3");
        Instant first = Instant.parse("2026-09-11T10:00:00Z");
        Instant second = Instant.parse("2026-09-11T10:05:00Z");

        writer.writeBatch(List.of(telemetry(vehicleId, first, 40.40, -3.70)));
        writer.writeBatch(List.of(telemetry(vehicleId, second, 40.41, -3.71)));

        VehicleState state = readVehicleState(vehicleId);
        assertThat(state.recordedAt()).isEqualTo(second);
        assertThat(state.lat()).isEqualTo(40.41);
        assertThat(state.lon()).isEqualTo(-3.71);
    }

    // Companion case: no pre-seeded vehicle_state row exists before the
    // first telemetry message ever arrives for a vehicle -- the writer must
    // lazily create it, not assume a row is already there.
    @Test
    void firstEverMessageForAVehicleLazilyCreatesVehicleState() throws Exception {
        migrate();
        JdbcTelemetryPositionWriter writer = newWriter();
        UUID vehicleId = seedVehicle("Truck-WU6-4");
        Instant recordedAt = Instant.parse("2026-09-11T10:00:00Z");
        assertThat(vehicleStateRowExists(vehicleId)).isFalse();

        writer.writeBatch(List.of(telemetry(vehicleId, recordedAt, 40.40, -3.70)));

        assertThat(vehicleStateRowExists(vehicleId)).isTrue();
        VehicleState state = readVehicleState(vehicleId);
        assertThat(state.recordedAt()).isEqualTo(recordedAt);
    }

    // Task 4.2: MotionDetector is only applied once the guard above already
    // accepts a message as newer -- proven here by driving the SAME
    // upsert two flushes apart, sustaining a high-speed sample long enough
    // to cross MotionConfig.minStableDuration() (30s, matching
    // newMotionStreakTracker()'s fixture).
    @Test
    void sustainedHighSpeedAcrossTwoMessagesTransitionsVehicleStateToMoving() throws Exception {
        migrate();
        JdbcTelemetryPositionWriter writer = newWriter();
        UUID vehicleId = seedVehicle("Truck-WU7-1");
        Instant first = Instant.parse("2026-09-11T10:00:00Z");
        Instant second = first.plusSeconds(31);

        writer.writeBatch(List.of(telemetry(vehicleId, first, 40.40, -3.70, 20.0)));
        assertThat(readMotionState(vehicleId)).isEqualTo("STOPPED");

        writer.writeBatch(List.of(telemetry(vehicleId, second, 40.41, -3.71, 20.0)));

        assertThat(readMotionState(vehicleId)).isEqualTo(MotionState.MOVING.name());
    }

    // Task 4.2 composed with task 4.1's guard: a stale resend must not
    // regress motion_state any more than it regresses location/recorded_at
    // -- both are set in the very same guarded SET clause.
    @Test
    void staleMessageAfterReachingMovingDoesNotRegressMotionState() throws Exception {
        migrate();
        JdbcTelemetryPositionWriter writer = newWriter();
        UUID vehicleId = seedVehicle("Truck-WU7-2");
        Instant first = Instant.parse("2026-09-11T10:00:00Z");
        Instant second = first.plusSeconds(31);
        Instant stale = first.minusSeconds(3600);

        writer.writeBatch(List.of(telemetry(vehicleId, first, 40.40, -3.70, 20.0)));
        writer.writeBatch(List.of(telemetry(vehicleId, second, 40.41, -3.71, 20.0)));
        assertThat(readMotionState(vehicleId)).isEqualTo(MotionState.MOVING.name());

        writer.writeBatch(List.of(telemetry(vehicleId, stale, 0.0, 0.0, 0.0)));

        assertThat(readMotionState(vehicleId)).isEqualTo(MotionState.MOVING.name());
    }

    // Task 4.2: a message without speedKmh cannot be evaluated by
    // MotionDetector (VehicleMotionStreakTrackerTest already proves the
    // pure computation) -- at the JDBC boundary this means motion_state
    // simply stays NULL for a vehicle's first-ever message when no speed
    // was ever reported, same as before this task existed.
    @Test
    void firstEverMessageWithoutSpeedLeavesMotionStateNull() throws Exception {
        migrate();
        JdbcTelemetryPositionWriter writer = newWriter();
        UUID vehicleId = seedVehicle("Truck-WU7-3");

        writer.writeBatch(List.of(telemetry(vehicleId, Instant.parse("2026-09-11T10:00:00Z"), 40.40, -3.70)));

        assertThat(readMotionState(vehicleId)).isNull();
    }

    private static void migrate() {
        Flyway.configure().dataSource(postgis.getJdbcUrl(), postgis.getUsername(), postgis.getPassword()).load().migrate();
    }

    private static JdbcTemplate newJdbcTemplate() {
        return new JdbcTemplate(new DriverManagerDataSource(postgis.getJdbcUrl(), postgis.getUsername(), postgis.getPassword()));
    }

    private static JdbcTelemetryPositionWriter newWriter() {
        return new JdbcTelemetryPositionWriter(newJdbcTemplate(), newMotionStreakTracker());
    }

    private static VehicleMotionStreakTracker newMotionStreakTracker() {
        return new VehicleMotionStreakTracker(new FleetpulseMotionDetectionProperties(5.0, 12.0, Duration.ofSeconds(30)));
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(postgis.getJdbcUrl(), postgis.getUsername(), postgis.getPassword());
    }

    private static int countPositions(UUID vehicleId) throws SQLException {
        try (
            Connection connection = connect();
            PreparedStatement statement = connection.prepareStatement("SELECT count(*) FROM positions WHERE vehicle_id = ?")
        ) {
            statement.setObject(1, vehicleId);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).isTrue();
                return resultSet.getInt(1);
            }
        }
    }

    private static boolean positionExists(UUID vehicleId, Instant recordedAt) throws SQLException {
        try (
            Connection connection = connect();
            PreparedStatement statement = connection
                .prepareStatement("SELECT count(*) FROM positions WHERE vehicle_id = ? AND recorded_at = ?")
        ) {
            statement.setObject(1, vehicleId);
            statement.setTimestamp(2, Timestamp.from(recordedAt));
            try (ResultSet resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).isTrue();
                return resultSet.getInt(1) == 1;
            }
        }
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

    private static VehicleState readVehicleState(UUID vehicleId) throws SQLException {
        try (
            Connection connection = connect();
            PreparedStatement statement = connection.prepareStatement(
                "SELECT recorded_at, ST_Y(location::geometry) AS lat, ST_X(location::geometry) AS lon "
                    + "FROM vehicle_state WHERE vehicle_id = ?"
            )
        ) {
            statement.setObject(1, vehicleId);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).isTrue();
                Instant recordedAt = resultSet.getTimestamp("recorded_at").toInstant();
                double lat = resultSet.getDouble("lat");
                double lon = resultSet.getDouble("lon");
                return new VehicleState(recordedAt, lat, lon);
            }
        }
    }

    private record VehicleState(Instant recordedAt, double lat, double lon) {
    }

    private static String readMotionState(UUID vehicleId) throws SQLException {
        try (
            Connection connection = connect();
            PreparedStatement statement = connection.prepareStatement("SELECT motion_state FROM vehicle_state WHERE vehicle_id = ?")
        ) {
            statement.setObject(1, vehicleId);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).isTrue();
                return resultSet.getString("motion_state");
            }
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

    private static TelemetryMessage telemetry(UUID vehicleId, Instant recordedAt, double lat, double lon) {
        return new TelemetryMessage(vehicleId, recordedAt, lat, lon, null, null, null);
    }

    private static TelemetryMessage telemetry(UUID vehicleId, Instant recordedAt, double lat, double lon, Double speedKmh) {
        return new TelemetryMessage(vehicleId, recordedAt, lat, lon, speedKmh, null, false);
    }
}

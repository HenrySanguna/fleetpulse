package dev.fleetpulse.processor.telemetry;

import dev.fleetpulse.processor.config.FleetpulseEtaProperties;
import dev.fleetpulse.processor.config.FleetpulseGeofencingProperties;
import dev.fleetpulse.processor.config.FleetpulseMotionDetectionProperties;
import dev.fleetpulse.processor.eta.EtaRecalculationDispatcher;
import dev.fleetpulse.processor.eta.JdbcRecentSpeedReader;
import dev.fleetpulse.processor.eta.JdbcVehicleDestinationEtaWriter;
import dev.fleetpulse.processor.eta.JdbcVehicleDestinationReader;
import dev.fleetpulse.processor.eta.SinuosityEtaCalculator;
import dev.fleetpulse.processor.geofencing.GeofenceEvaluator;
import dev.fleetpulse.processor.geofencing.GeofenceRuleDispatcher;
import dev.fleetpulse.processor.geofencing.JdbcGeofenceAlertWriter;
import dev.fleetpulse.processor.geofencing.JdbcVehicleFenceStateWriter;
import dev.fleetpulse.processor.config.FleetpulseTelemetryImplausibilityProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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

// Tasks 3.1-3.3 wired against a real PostGIS database (same Dockerfile image
// domain's/WU1's/WU2's schema tests use): proves JdbcTelemetryPositionWriter's
// batchUpdate/ON CONFLICT DO NOTHING against the real `positions` table
// (task 3.2), and drives TelemetryImplausibilityFilter + TelemetryPositionBuffer
// directly -- the way a later work unit's TelemetryMessageListener will call
// them -- to prove test 6.5 (implausible position discarded, never
// persisted) and test 6.10 (orderly shutdown flushes the pending buffer). No
// MQTT broker is involved: per the finalized work-unit forecast, this stays
// provable without WU3's live consumer running.
@Testcontainers
class TelemetryBatchWriteTest {

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
    void writerPersistsAValidPositionAsAGeographyPoint() throws Exception {
        migrate();
        JdbcTelemetryPositionWriter writer = newWriter();
        UUID vehicleId = seedVehicle("Truck-WU4-1");
        Instant recordedAt = Instant.now();
        TelemetryMessage message = telemetry(vehicleId, recordedAt, 40.4, -3.7, 55.0, 180.0, true);

        writer.writeBatch(List.of(message));

        try (
            Connection connection = connect();
            PreparedStatement statement = connection.prepareStatement(
                "SELECT speed_kmh, heading, ignition, ST_X(location::geometry) AS lon, ST_Y(location::geometry) AS lat "
                    + "FROM positions WHERE vehicle_id = ? AND recorded_at = ?"
            )
        ) {
            statement.setObject(1, vehicleId);
            statement.setTimestamp(2, Timestamp.from(recordedAt));
            try (ResultSet resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).isTrue();
                assertThat(resultSet.getFloat("speed_kmh")).isEqualTo(55.0f);
                assertThat(resultSet.getFloat("heading")).isEqualTo(180.0f);
                assertThat(resultSet.getBoolean("ignition")).isTrue();
                assertThat(resultSet.getDouble("lat")).isEqualTo(40.4);
                assertThat(resultSet.getDouble("lon")).isEqualTo(-3.7);
            }
        }
    }

    @Test
    void writerSilentlyDropsAResentDuplicateViaOnConflictDoNothing() throws Exception {
        migrate();
        JdbcTelemetryPositionWriter writer = newWriter();
        UUID vehicleId = seedVehicle("Truck-WU4-2");
        Instant recordedAt = Instant.now();

        writer.writeBatch(List.of(telemetry(vehicleId, recordedAt, 40.4, -3.7, null, null, null)));
        writer.writeBatch(List.of(telemetry(vehicleId, recordedAt, 40.4, -3.7, null, null, null)));

        assertThat(countPositions(vehicleId)).isEqualTo(1);
    }

    // Test 6.5: the filter's rejection happens before the buffer ever sees
    // the message, so the implausible jump never reaches `positions` --
    // only the plausible position does.
    @Test
    void implausiblePositionDiscardedByTheFilterIsNeverPersisted() throws Exception {
        migrate();
        UUID vehicleId = seedVehicle("Truck-WU4-3");
        TelemetryImplausibilityFilter filter = new TelemetryImplausibilityFilter(
            new FleetpulseTelemetryImplausibilityProperties(300.0), new SimpleMeterRegistry()
        );
        TelemetryPositionBuffer buffer = new TelemetryPositionBuffer(
            1000, Duration.ofMinutes(10), newWriter()
        );
        buffer.start();
        try {
            Instant now = Instant.now();
            TelemetryMessage plausible = telemetry(vehicleId, now, 40.4, -3.7, null, null, null);
            TelemetryMessage implausibleJump = telemetry(vehicleId, now.plusSeconds(1), 45.0, -3.7, null, null, null);

            if (filter.isPlausible(plausible)) {
                buffer.add(plausible);
            }
            if (filter.isPlausible(implausibleJump)) {
                buffer.add(implausibleJump);
            }
            buffer.stop();

            assertThat(countPositions(vehicleId)).isEqualTo(1);
        } finally {
            buffer.stop();
        }
    }

    // Test 6.10: stop() must flush whatever is still buffered before
    // returning, with neither the size nor the time trigger having fired.
    @Test
    void orderlyShutdownFlushesThePendingBufferBeforeStopReturns() throws Exception {
        migrate();
        UUID vehicleId = seedVehicle("Truck-WU4-4");
        TelemetryPositionBuffer buffer = new TelemetryPositionBuffer(
            1000, Duration.ofMinutes(10), newWriter()
        );
        buffer.start();

        Instant now = Instant.now();
        buffer.add(telemetry(vehicleId, now, 40.4, -3.7, null, null, null));
        buffer.add(telemetry(vehicleId, now.plusSeconds(5), 40.41, -3.71, null, null, null));
        assertThat(countPositions(vehicleId)).isEqualTo(0);

        buffer.stop();

        assertThat(countPositions(vehicleId)).isEqualTo(2);
    }

    private static void migrate() {
        Flyway.configure().dataSource(postgis.getJdbcUrl(), postgis.getUsername(), postgis.getPassword()).load().migrate();
    }

    private static JdbcTemplate newJdbcTemplate() {
        return new JdbcTemplate(new DriverManagerDataSource(postgis.getJdbcUrl(), postgis.getUsername(), postgis.getPassword()));
    }

    private static JdbcTelemetryPositionWriter newWriter() {
        JdbcTemplate jdbcTemplate = newJdbcTemplate();
        return new JdbcTelemetryPositionWriter(
            jdbcTemplate, newMotionStreakTracker(), newGeofenceRuleDispatcher(jdbcTemplate), newEtaRecalculationDispatcher(jdbcTemplate)
        );
    }

    private static VehicleMotionStreakTracker newMotionStreakTracker() {
        return new VehicleMotionStreakTracker(new FleetpulseMotionDetectionProperties(5.0, 12.0, Duration.ofSeconds(30)));
    }

    // Task 2.4/WU4: no geofences are ever seeded by this test, so this
    // dispatcher will always find zero relevant geofences to evaluate --
    // wired with a real GeofenceEvaluator/JdbcVehicleFenceStateWriter/
    // JdbcGeofenceAlertWriter against the same database, but a no-op
    // GeofenceAlertPublisher, since this test has no broker and no use for
    // one (this class's own tests, GeofenceEvaluatorTest and
    // GeofenceRuleEngineTest, already cover the geofencing behavior itself).
    private static GeofenceRuleDispatcher newGeofenceRuleDispatcher(JdbcTemplate jdbcTemplate) {
        return new GeofenceRuleDispatcher(
            jdbcTemplate,
            new GeofenceEvaluator(jdbcTemplate),
            new FleetpulseGeofencingProperties(3, Duration.ofSeconds(30), 15.0),
            alert -> { },
            new JdbcVehicleFenceStateWriter(jdbcTemplate),
            new JdbcGeofenceAlertWriter(jdbcTemplate)
        );
    }

    // Task 2.4/WU2: no destinations are ever assigned by this test, so this
    // dispatcher always finds zero active destinations to recalculate --
    // mirrors newGeofenceRuleDispatcher's own identical reasoning above.
    private static EtaRecalculationDispatcher newEtaRecalculationDispatcher(JdbcTemplate jdbcTemplate) {
        FleetpulseEtaProperties etaProperties = new FleetpulseEtaProperties(1.3, 0.3, 5.0, 30.0, Duration.ofMinutes(15));
        return new EtaRecalculationDispatcher(
            new JdbcVehicleDestinationReader(jdbcTemplate),
            new JdbcRecentSpeedReader(jdbcTemplate),
            new SinuosityEtaCalculator(etaProperties),
            new JdbcVehicleDestinationEtaWriter(jdbcTemplate),
            (organizationId, vehicleId, estimate, calculatedAt) -> { },
            etaProperties
        );
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

    private static TelemetryMessage telemetry(
        UUID vehicleId, Instant recordedAt, double lat, double lon, Double speedKmh, Double heading, Boolean ignition
    ) {
        return new TelemetryMessage(vehicleId, recordedAt, lat, lon, speedKmh, heading, ignition);
    }
}

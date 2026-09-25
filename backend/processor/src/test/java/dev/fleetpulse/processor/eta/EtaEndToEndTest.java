package dev.fleetpulse.processor.eta;

import dev.fleetpulse.processor.alerts.AlertRuleDispatcher;
import dev.fleetpulse.processor.alerts.JdbcAlertSilenceStateStore;
import dev.fleetpulse.processor.alerts.JdbcAlertWriter;
import dev.fleetpulse.processor.config.FleetpulseAlertingProperties;
import dev.fleetpulse.processor.config.FleetpulseEtaProperties;
import dev.fleetpulse.processor.config.FleetpulseGeofencingProperties;
import dev.fleetpulse.processor.config.FleetpulseMotionDetectionProperties;
import dev.fleetpulse.processor.geofencing.GeofenceEvaluator;
import dev.fleetpulse.processor.geofencing.GeofenceRuleDispatcher;
import dev.fleetpulse.processor.geofencing.JdbcGeofenceAlertWriter;
import dev.fleetpulse.processor.geofencing.JdbcVehicleFenceStateWriter;
import dev.fleetpulse.processor.telemetry.JdbcTelemetryPositionWriter;
import dev.fleetpulse.processor.telemetry.TelemetryMessage;
import dev.fleetpulse.processor.telemetry.VehicleMotionStreakTracker;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// Task 2.4 ("recalculo en el flujo en vivo y publicacion al topico de la
// organizacion"): single PostGIS container, no Mosquitto -- the same
// deviation TripSegmentationEndToEndTest (WU1) already documents for its own
// reason, applying here for a DIFFERENT one: EtaPublisher is EtaRecalculationDispatcher's
// own seam (this test substitutes a capturing test double for it, the same
// way GeofenceRuleDispatcher's own tests substitute a no-op/capturing
// GeofenceAlertPublisher), so a real broker proves nothing about the
// dispatch logic itself; MqttEtaPublisher/EtaMqttConfig's own wiring is
// exercised implicitly by every @SpringBootTest that boots the full
// processor context (TelemetryEndToEndIngestTest and friends).
//
// Positions/destinations are inserted directly via JDBC and run through the
// REAL wired live path: JdbcTelemetryPositionWriter.writeBatch() ->
// EtaRecalculationDispatcher -> JdbcVehicleDestinationReader/
// JdbcRecentSpeedReader -> SinuosityEtaCalculator -> JdbcVehicleDestinationEtaWriter
// (+ the capturing EtaPublisher), exactly the path a real live telemetry
// message takes.
@Testcontainers
class EtaEndToEndTest {

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

    private static final Instant NOW = Instant.now().minus(Duration.ofHours(3));

    @Test
    void assigningADestinationAndReceivingALivePositionComputesAndPersistsAnEtaWithAMargin() throws Exception {
        migrate();
        List<PublishedEta> published = new ArrayList<>();
        JdbcTelemetryPositionWriter writer = newWriter(published);

        UUID organizationId;
        UUID vehicleId;
        try (Connection connection = connect()) {
            organizationId = insertOrganization(connection, "Acme Eta E2E Org 1");
            vehicleId = insertVehicle(connection, organizationId, "Truck-ETA-E2E-1");
            insertDestination(connection, vehicleId, organizationId, 4.80, -74.10, NOW.minusSeconds(60));
        }

        writer.writeBatch(List.of(new TelemetryMessage(vehicleId, NOW, 4.70, -74.05, 40.0, null, true)));

        try (Connection connection = connect()) {
            EtaRow row = readEtaRow(connection, vehicleId);
            assertThat(row.etaSeconds()).isNotNull().isGreaterThan(0);
            assertThat(row.etaMarginSeconds()).isNotNull().isGreaterThan(0);
            assertThat(row.etaCalculatedAt()).isNotNull();
        }

        assertThat(published).hasSize(1);
        assertThat(published.get(0).organizationId()).isEqualTo(organizationId);
        assertThat(published.get(0).vehicleId()).isEqualTo(vehicleId);
        assertThat(published.get(0).estimate().etaSeconds()).isGreaterThan(0);
        assertThat(published.get(0).estimate().marginSeconds()).isGreaterThan(0);
    }

    @Test
    void aVehicleWithNoAssignedDestinationGetsNoEtaComputedOrPublished() throws Exception {
        migrate();
        List<PublishedEta> published = new ArrayList<>();
        JdbcTelemetryPositionWriter writer = newWriter(published);

        UUID organizationId;
        UUID vehicleId;
        try (Connection connection = connect()) {
            organizationId = insertOrganization(connection, "Acme Eta E2E Org 2");
            vehicleId = insertVehicle(connection, organizationId, "Truck-ETA-E2E-2");
        }

        writer.writeBatch(List.of(new TelemetryMessage(vehicleId, NOW, 4.70, -74.05, 40.0, null, true)));

        try (Connection connection = connect()) {
            assertThat(destinationRowExists(connection, vehicleId)).isFalse();
        }
        assertThat(published).isEmpty();
    }

    // Task 2.2's "velocidad media reciente del vehiculo": two vehicles with
    // the SAME destination and current position, but different recent
    // `positions` history, must get DIFFERENT ETAs -- proving the recent
    // average speed genuinely came from real telemetry (JdbcRecentSpeedReader),
    // not a constant shared by every vehicle regardless of its own recent
    // driving.
    @Test
    void theRecentAverageSpeedComesFromRealRecentPositionsNotAFixedConstant() throws Exception {
        migrate();
        List<PublishedEta> published = new ArrayList<>();
        JdbcTelemetryPositionWriter writer = newWriter(published);

        UUID organizationId;
        UUID fastVehicleId;
        UUID slowVehicleId;
        try (Connection connection = connect()) {
            organizationId = insertOrganization(connection, "Acme Eta E2E Org 3");
            fastVehicleId = insertVehicle(connection, organizationId, "Truck-ETA-E2E-Fast");
            slowVehicleId = insertVehicle(connection, organizationId, "Truck-ETA-E2E-Slow");
            insertDestination(connection, fastVehicleId, organizationId, 4.90, -74.05, NOW.minusSeconds(60));
            insertDestination(connection, slowVehicleId, organizationId, 4.90, -74.05, NOW.minusSeconds(60));

            // Fast vehicle: ~5 km covered in 5 minutes -- a realistic ~60
            // km/h recent average speed, comfortably above the 5.0 km/h
            // floor (FleetpulseEtaProperties.minEffectiveSpeedKmh).
            insertPosition(connection, fastVehicleId, NOW.minusSeconds(300), 4.70, -74.095);
            // Slow vehicle: a tiny recent displacement in the same 5 minutes
            // -- a real recent average speed well BELOW the floor, so the
            // calculator clamps it up (its own documented, deliberate
            // behavior), still leaving it slower than the fast vehicle's
            // genuinely-computed, unclamped average.
            insertPosition(connection, slowVehicleId, NOW.minusSeconds(300), 4.70, -74.0505);
        }

        writer.writeBatch(List.of(
            new TelemetryMessage(fastVehicleId, NOW, 4.70, -74.05, null, null, true),
            new TelemetryMessage(slowVehicleId, NOW, 4.70, -74.05, null, null, true)
        ));

        try (Connection connection = connect()) {
            EtaRow fastRow = readEtaRow(connection, fastVehicleId);
            EtaRow slowRow = readEtaRow(connection, slowVehicleId);
            // Same destination, same current position: a faster recent
            // average speed must produce a SHORTER eta than a slower one.
            assertThat(fastRow.etaSeconds()).isLessThan(slowRow.etaSeconds());
        }
    }

    private static JdbcTelemetryPositionWriter newWriter(List<PublishedEta> published) {
        JdbcTemplate jdbcTemplate = newJdbcTemplate();
        return new JdbcTelemetryPositionWriter(
            jdbcTemplate, newMotionStreakTracker(), newGeofenceRuleDispatcher(jdbcTemplate),
            newEtaRecalculationDispatcher(jdbcTemplate, published), newAlertRuleDispatcher(jdbcTemplate)
        );
    }

    private static VehicleMotionStreakTracker newMotionStreakTracker() {
        return new VehicleMotionStreakTracker(new FleetpulseMotionDetectionProperties(5.0, 12.0, Duration.ofSeconds(30)));
    }

    private static GeofenceRuleDispatcher newGeofenceRuleDispatcher(JdbcTemplate jdbcTemplate) {
        return new GeofenceRuleDispatcher(
            jdbcTemplate,
            new GeofenceEvaluator(jdbcTemplate),
            new FleetpulseGeofencingProperties(3, Duration.ofSeconds(30), 15.0, Duration.ofMinutes(10)),
            alert -> { },
            new JdbcVehicleFenceStateWriter(jdbcTemplate),
            new JdbcGeofenceAlertWriter(jdbcTemplate),
            new JdbcAlertSilenceStateStore(jdbcTemplate)
        );
    }

    private static EtaRecalculationDispatcher newEtaRecalculationDispatcher(JdbcTemplate jdbcTemplate, List<PublishedEta> published) {
        FleetpulseEtaProperties etaProperties = new FleetpulseEtaProperties(1.3, 0.3, 5.0, 30.0, Duration.ofMinutes(15));
        return new EtaRecalculationDispatcher(
            new JdbcVehicleDestinationReader(jdbcTemplate),
            new JdbcRecentSpeedReader(jdbcTemplate),
            new SinuosityEtaCalculator(etaProperties),
            new JdbcVehicleDestinationEtaWriter(jdbcTemplate),
            (organizationId, vehicleId, estimate, calculatedAt) ->
                published.add(new PublishedEta(organizationId, vehicleId, estimate)),
            etaProperties
        );
    }

    // Task 3.2/WU3: this test proves ETA recalculation, not alerting -- same
    // "no-op publisher, real writer/silence-state store" reasoning
    // newGeofenceRuleDispatcher's own comment documents for geofencing.
    private static AlertRuleDispatcher newAlertRuleDispatcher(JdbcTemplate jdbcTemplate) {
        return new AlertRuleDispatcher(
            jdbcTemplate,
            new FleetpulseAlertingProperties(100.0, Duration.ofMinutes(10), Duration.ofMinutes(15)),
            new JdbcAlertWriter(jdbcTemplate),
            alert -> { },
            new JdbcAlertSilenceStateStore(jdbcTemplate)
        );
    }

    private record PublishedEta(UUID organizationId, UUID vehicleId, EtaEstimate estimate) {
    }

    private static void migrate() {
        Flyway.configure().dataSource(postgis.getJdbcUrl(), postgis.getUsername(), postgis.getPassword()).load().migrate();
    }

    private static JdbcTemplate newJdbcTemplate() {
        return new JdbcTemplate(new DriverManagerDataSource(postgis.getJdbcUrl(), postgis.getUsername(), postgis.getPassword()));
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

    private static void insertPosition(Connection connection, UUID vehicleId, Instant recordedAt, double lat, double lon) throws SQLException {
        try (
            PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO positions (vehicle_id, recorded_at, location) "
                    + "VALUES (?, ?, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography)"
            )
        ) {
            statement.setObject(1, vehicleId);
            statement.setTimestamp(2, Timestamp.from(recordedAt));
            statement.setDouble(3, lon);
            statement.setDouble(4, lat);
            statement.executeUpdate();
        }
    }

    private static boolean destinationRowExists(Connection connection, UUID vehicleId) throws SQLException {
        try (
            PreparedStatement statement = connection
                .prepareStatement("SELECT count(*) FROM vehicle_destinations WHERE vehicle_id = ?")
        ) {
            statement.setObject(1, vehicleId);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).isTrue();
                return resultSet.getInt(1) > 0;
            }
        }
    }

    private static EtaRow readEtaRow(Connection connection, UUID vehicleId) throws SQLException {
        try (
            PreparedStatement statement = connection.prepareStatement(
                "SELECT eta_seconds, eta_margin_seconds, eta_calculated_at FROM vehicle_destinations WHERE vehicle_id = ?"
            )
        ) {
            statement.setObject(1, vehicleId);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).isTrue();
                Integer etaSeconds = (Integer) resultSet.getObject("eta_seconds");
                Integer etaMarginSeconds = (Integer) resultSet.getObject("eta_margin_seconds");
                Timestamp etaCalculatedAt = resultSet.getTimestamp("eta_calculated_at");
                return new EtaRow(etaSeconds, etaMarginSeconds, etaCalculatedAt == null ? null : etaCalculatedAt.toInstant());
            }
        }
    }

    private record EtaRow(Integer etaSeconds, Integer etaMarginSeconds, Instant etaCalculatedAt) {
    }
}

package dev.fleetpulse.processor.trips;

import dev.fleetpulse.processor.config.FleetpulseMotionDetectionProperties;
import dev.fleetpulse.processor.config.FleetpulseTripsProperties;
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
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

// Single PostGIS container, no Mosquitto: design.md is explicit that trip
// segmentation runs entirely over already-persisted `positions`, never on
// the live MQTT ingestion path ("no en el camino de ingesta") -- unlike
// 05-add-geofencing's own dual-container E2E tests (WU5), a broker is not
// part of this feature's real runtime path at all, so adding one here would
// not make this test more honest, only slower.
//
// Positions are inserted directly via JDBC (bypassing MQTT entirely, the
// same way GeofenceEvaluatorTest/TripSchemaTest insert rows directly) and
// run through the REAL wired pipeline: JdbcTripReader -> the SAME
// VehicleMotionStreakTracker bean the live write path uses (task 1.2's own
// design-gap resolution, see TripSegmenter's class comment) -> TripSegmenter
// -> JdbcTripWriter, orchestrated by TripSegmentationTask.segmentAllVehicles().
@Testcontainers
class TripSegmentationEndToEndTest {

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

    // Comfortably in the past (well past both FleetpulseTripsProperties'
    // default 10-minute processing delay and any org's stop threshold used
    // below), but still safely inside the SAME weekly partition `now` falls
    // in (V6's create_parent() pre-creates the current week's partition
    // plus 4 weeks ahead at migration time).
    private static final Instant BASE = Instant.now().minus(Duration.ofHours(3));

    // Task 1.3: real MotionDetector confirmation (30s debounce, the default
    // FleetpulseMotionDetectionProperties fixture) needs two consecutive
    // above-start-threshold readings before MOVING is confirmed, then two
    // consecutive below-stop-threshold readings before STOPPED is confirmed
    // -- this trace is built to cross both confirmations explicitly, not by
    // directly asserting a MotionState like TripSegmenterTest's pure fixture
    // does.
    @Test
    void perOrganizationStopThresholdDecidesWhetherTheSameStopClosesATrip() throws Exception {
        migrate();
        UUID shortThresholdVehicleId;
        UUID longThresholdVehicleId;

        try (Connection connection = connect()) {
            UUID shortThresholdOrgId = insertOrganization(connection, "Acme Short Threshold Org", 60);
            UUID longThresholdOrgId = insertOrganization(connection, "Acme Long Threshold Org", 1800);
            shortThresholdVehicleId = insertVehicle(connection, shortThresholdOrgId, "Truck-E2E-Short");
            longThresholdVehicleId = insertVehicle(connection, longThresholdOrgId, "Truck-E2E-Long");

            insertMovingThenStoppedTrace(connection, shortThresholdVehicleId);
            insertMovingThenStoppedTrace(connection, longThresholdVehicleId);
        }

        newTask().segmentAllVehicles();

        try (Connection connection = connect()) {
            List<TripRow> shortThresholdTrips = selectTrips(connection, shortThresholdVehicleId);
            assertThat(shortThresholdTrips).hasSize(1);
            assertThat(shortThresholdTrips.get(0).startedAt()).isCloseTo(BASE, within(1, ChronoUnit.MILLIS));
            assertThat(shortThresholdTrips.get(0).endedAt()).isCloseTo(BASE.plusSeconds(90), within(1, ChronoUnit.MILLIS));

            List<TripRow> longThresholdTrips = selectTrips(connection, longThresholdVehicleId);
            assertThat(longThresholdTrips).isEmpty();
        }
    }

    // Tasks 1.5/5.3: running segmentAllVehicles() twice over the same
    // already-covered history never duplicates or alters the trip closed by
    // the first run -- JdbcTripReader's watermark (last closed trip's
    // ended_at) plus uq_trips_vehicle_started_at's ON CONFLICT DO NOTHING
    // both have to hold for this to be true.
    @Test
    void reprocessingTheSameWindowDoesNotDuplicateOrAlterAlreadyClosedTrips() throws Exception {
        migrate();
        UUID organizationId;
        UUID vehicleId;

        try (Connection connection = connect()) {
            organizationId = insertOrganization(connection, "Acme Idempotency Org", 60);
            vehicleId = insertVehicle(connection, organizationId, "Truck-E2E-Idempotent");
            insertMovingThenStoppedTrace(connection, vehicleId);
        }

        TripSegmentationTask task = newTask();
        task.segmentAllVehicles();

        UUID firstRunTripId;
        try (Connection connection = connect()) {
            List<TripRow> trips = selectTrips(connection, vehicleId);
            assertThat(trips).hasSize(1);
            firstRunTripId = trips.get(0).id();
        }

        task.segmentAllVehicles();

        try (Connection connection = connect()) {
            List<TripRow> trips = selectTrips(connection, vehicleId);
            assertThat(trips).hasSize(1);
            assertThat(trips.get(0).id()).isEqualTo(firstRunTripId);
            assertThat(trips.get(0).startedAt()).isCloseTo(BASE, within(1, ChronoUnit.MILLIS));
            assertThat(trips.get(0).endedAt()).isCloseTo(BASE.plusSeconds(90), within(1, ChronoUnit.MILLIS));
        }
    }

    // pos1(t=0,40kmh) pos2(t=30,40kmh) -> MOVING confirmed at pos2.
    // pos3(t=60,40kmh) -> still MOVING.
    // pos4(t=90,0kmh) -> streak starts, not yet stable, stays MOVING.
    // pos5(t=120,0kmh) -> stable 30s below stop threshold -> STOPPED confirmed.
    // pos6(t=150,0kmh), pos7(t=180,0kmh) -> stays STOPPED, extending the
    // not-moving run to 60s (measured from pos5) by pos7.
    private static void insertMovingThenStoppedTrace(Connection connection, UUID vehicleId) throws SQLException {
        insertPosition(connection, vehicleId, BASE, 40.0, false);
        insertPosition(connection, vehicleId, BASE.plusSeconds(30), 40.0, false);
        insertPosition(connection, vehicleId, BASE.plusSeconds(60), 40.0, false);
        insertPosition(connection, vehicleId, BASE.plusSeconds(90), 0.0, false);
        insertPosition(connection, vehicleId, BASE.plusSeconds(120), 0.0, false);
        insertPosition(connection, vehicleId, BASE.plusSeconds(150), 0.0, false);
        insertPosition(connection, vehicleId, BASE.plusSeconds(180), 0.0, false);
    }

    private static TripSegmentationTask newTask() {
        JdbcTemplate jdbcTemplate = newJdbcTemplate();
        JdbcTripReader tripReader = new JdbcTripReader(jdbcTemplate);
        JdbcTripWriter tripWriter = new JdbcTripWriter(jdbcTemplate);
        VehicleMotionStreakTracker motionStreakTracker = new VehicleMotionStreakTracker(
            new FleetpulseMotionDetectionProperties(5, 12, Duration.ofSeconds(30))
        );
        FleetpulseTripsProperties tripsProperties = new FleetpulseTripsProperties(Duration.ofMinutes(10));
        return new TripSegmentationTask(tripReader, tripWriter, motionStreakTracker, tripsProperties);
    }

    private static JdbcTemplate newJdbcTemplate() {
        return new JdbcTemplate(new DriverManagerDataSource(postgis.getJdbcUrl(), postgis.getUsername(), postgis.getPassword()));
    }

    private static void migrate() {
        Flyway.configure().dataSource(postgis.getJdbcUrl(), postgis.getUsername(), postgis.getPassword()).load().migrate();
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(postgis.getJdbcUrl(), postgis.getUsername(), postgis.getPassword());
    }

    private static UUID insertOrganization(Connection connection, String name, int tripStopThresholdSecs) throws SQLException {
        UUID id = UUID.randomUUID();
        try (
            PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO organizations (id, name, created_at, trip_stop_threshold_secs) VALUES (?, ?, ?, ?)"
            )
        ) {
            statement.setObject(1, id);
            statement.setString(2, name);
            statement.setTimestamp(3, Timestamp.from(Instant.now()));
            statement.setInt(4, tripStopThresholdSecs);
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

    private static void insertPosition(
        Connection connection, UUID vehicleId, Instant recordedAt, double speedKmh, boolean ignition
    ) throws SQLException {
        try (
            PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO positions (vehicle_id, recorded_at, location, speed_kmh, ignition) "
                    + "VALUES (?, ?, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography, ?, ?)"
            )
        ) {
            statement.setObject(1, vehicleId);
            statement.setTimestamp(2, Timestamp.from(recordedAt));
            statement.setDouble(3, -74.07);
            statement.setDouble(4, 4.71);
            statement.setFloat(5, (float) speedKmh);
            statement.setBoolean(6, ignition);
            statement.executeUpdate();
        }
    }

    private static List<TripRow> selectTrips(Connection connection, UUID vehicleId) throws SQLException {
        List<TripRow> trips = new ArrayList<>();
        try (
            PreparedStatement statement = connection
                .prepareStatement("SELECT id, started_at, ended_at FROM trips WHERE vehicle_id = ? ORDER BY started_at")
        ) {
            statement.setObject(1, vehicleId);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    trips.add(new TripRow(
                        (UUID) resultSet.getObject("id"),
                        resultSet.getTimestamp("started_at").toInstant(),
                        resultSet.getTimestamp("ended_at").toInstant()
                    ));
                }
            }
        }
        return trips;
    }

    private record TripRow(UUID id, Instant startedAt, Instant endedAt) {
    }
}

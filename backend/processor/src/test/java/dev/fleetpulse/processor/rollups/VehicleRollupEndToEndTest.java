package dev.fleetpulse.processor.rollups;

import dev.fleetpulse.processor.config.FleetpulseMotionDetectionProperties;
import dev.fleetpulse.processor.config.FleetpulseRollupsProperties;
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
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

// Single PostGIS container, no Mosquitto -- same deviation
// TripSegmentationEndToEndTest documents and for the same reason: rollups
// run entirely over already-persisted `positions`, never on the live MQTT
// ingestion path (VehicleRollupTask's own class comment), so a broker is not
// part of this feature's real runtime path either.
//
// Positions are inserted directly via JDBC (bypassing MQTT, same as
// TripSegmentationEndToEndTest) and run through the REAL wired pipeline:
// JdbcRollupReader -> the SAME VehicleMotionStreakTracker bean the live
// write path/trip segmentation already use -> HourlyRollupAggregator ->
// JdbcHourlyRollupWriter -> JdbcDailyRollupWriter, orchestrated by
// VehicleRollupTask.recalculateAllVehicles().
@Testcontainers
class VehicleRollupEndToEndTest {

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

    // Hour-aligned and comfortably in the past: well inside a CLOSED hour
    // under the default 24h window (FleetpulseRollupsProperties), and far
    // enough from "now" that the trace below (spanning 180s) never crosses
    // into the still-open current hour.
    private static final Instant HOUR_BASE = Instant.now().minus(Duration.ofHours(5)).truncatedTo(ChronoUnit.HOURS);

    // Same real MotionDetector confirmation trace TripSegmentationEndToEndTest's
    // own insertMovingThenStoppedTrace uses (worked out in that test's class
    // comment): MOVING confirmed at t=30, STOPPED confirmed at t=120. Legs:
    // t0-30 (STOPPED->MOVING, transition), t30-60 and t60-90 (MOVING->MOVING,
    // 30s each -> moving_secs=60), t90-120 (MOVING->STOPPED, transition),
    // t120-150 and t150-180 (STOPPED->STOPPED, 30s each -> idle_secs=60).
    // Coordinates drift north while MOVING and stay fixed while STOPPED, so
    // distance_km is genuinely positive without needing exact geo-math in
    // the assertions below.
    @Test
    void recalculatingTheSameHourTwiceProducesTheSameResult() throws Exception {
        migrate();
        UUID vehicleId;
        try (Connection connection = connect()) {
            UUID organizationId = insertOrganization(connection, "Acme Rollup Idempotency Org");
            vehicleId = insertVehicle(connection, organizationId, "Truck-Rollup-Idempotent");
            insertStandardTrace(connection, vehicleId, HOUR_BASE);
        }

        VehicleRollupTask task = newTask();
        task.recalculateAllVehicles();

        HourlyRow firstRun;
        try (Connection connection = connect()) {
            firstRun = selectHourlyRow(connection, vehicleId, HOUR_BASE);
            assertThat(firstRun).isNotNull();
            assertThat(firstRun.movingSecs()).isEqualTo(60L);
            assertThat(firstRun.idleSecs()).isEqualTo(60L);
            assertThat(firstRun.maxSpeedKmh()).isEqualTo(40.0);
            assertThat(firstRun.distanceKm()).isGreaterThan(0.0);
        }

        task.recalculateAllVehicles();

        try (Connection connection = connect()) {
            HourlyRow secondRun = selectHourlyRow(connection, vehicleId, HOUR_BASE);
            assertThat(secondRun.distanceKm()).isEqualTo(firstRun.distanceKm());
            assertThat(secondRun.movingSecs()).isEqualTo(firstRun.movingSecs());
            assertThat(secondRun.idleSecs()).isEqualTo(firstRun.idleSecs());
            assertThat(secondRun.maxSpeedKmh()).isEqualTo(firstRun.maxSpeedKmh());
        }
    }

    // Test 5.5: a position for a timestamp already inside an hour that was
    // already recomputed once arrives LATE (inserted only after the first
    // recompute ran) -- spec.md's own "Llegada tardia de telemetria de un
    // periodo ya agregado" scenario. Recomputing again must fold it in.
    @Test
    void lateTelemetryForAnAlreadyRolledUpHourUpdatesItOnRecompute() throws Exception {
        migrate();
        UUID vehicleId;
        try (Connection connection = connect()) {
            UUID organizationId = insertOrganization(connection, "Acme Rollup Late Org");
            vehicleId = insertVehicle(connection, organizationId, "Truck-Rollup-Late");
            insertStandardTrace(connection, vehicleId, HOUR_BASE);
        }

        VehicleRollupTask task = newTask();
        task.recalculateAllVehicles();

        HourlyRow beforeLateArrival;
        try (Connection connection = connect()) {
            beforeLateArrival = selectHourlyRow(connection, vehicleId, HOUR_BASE);
            assertThat(beforeLateArrival.maxSpeedKmh()).isEqualTo(40.0);
        }

        try (Connection connection = connect()) {
            insertPosition(connection, vehicleId, HOUR_BASE.plusSeconds(75), 4.7500, -74.0700, 90.0, false);
        }

        task.recalculateAllVehicles();

        try (Connection connection = connect()) {
            HourlyRow afterLateArrival = selectHourlyRow(connection, vehicleId, HOUR_BASE);
            assertThat(afterLateArrival.maxSpeedKmh()).isEqualTo(90.0);
            assertThat(afterLateArrival.distanceKm()).isGreaterThan(beforeLateArrival.distanceKm());
        }
    }

    // design.md: "recalcula las horas cerradas recientes" -- the
    // still-in-progress current hour must never get a vehicle_hourly row.
    @Test
    void theCurrentOpenHourIsNeverRolledUp() throws Exception {
        migrate();
        UUID vehicleId;
        Instant currentHour = Instant.now().truncatedTo(ChronoUnit.HOURS);
        try (Connection connection = connect()) {
            UUID organizationId = insertOrganization(connection, "Acme Rollup Open Hour Org");
            vehicleId = insertVehicle(connection, organizationId, "Truck-Rollup-OpenHour");
            insertPosition(connection, vehicleId, Instant.now(), 4.71, -74.07, 40.0, false);
        }

        newTask().recalculateAllVehicles();

        try (Connection connection = connect()) {
            assertThat(selectHourlyRow(connection, vehicleId, currentHour)).isNull();
        }
    }

    // Task 4.2: vehicle_daily sums the SAME two hours' worth of
    // vehicle_hourly rows this same run just wrote.
    @Test
    void dailyRollupIsDerivedFromHourlyRollup() throws Exception {
        migrate();
        UUID vehicleId;
        Instant earlierHour = HOUR_BASE.minus(Duration.ofHours(1));
        try (Connection connection = connect()) {
            UUID organizationId = insertOrganization(connection, "Acme Rollup Daily Org");
            vehicleId = insertVehicle(connection, organizationId, "Truck-Rollup-Daily");
            insertStandardTrace(connection, vehicleId, HOUR_BASE);
            insertStandardTrace(connection, vehicleId, earlierHour);
        }

        newTask().recalculateAllVehicles();

        try (Connection connection = connect()) {
            HourlyRow hourA = selectHourlyRow(connection, vehicleId, HOUR_BASE);
            HourlyRow hourB = selectHourlyRow(connection, vehicleId, earlierHour);
            assertThat(hourA).isNotNull();
            assertThat(hourB).isNotNull();

            // HOUR_BASE and earlierHour (one hour apart) are assumed to fall
            // on the same UTC calendar day -- true except in the rare case
            // this suite happens to run between 00:00 and 01:00 UTC, the
            // same class of low-probability CI-time-dependent assumption
            // TripSegmentationEndToEndTest's own BASE constant already
            // relies on.
            LocalDate day = HOUR_BASE.atZone(ZoneOffset.UTC).toLocalDate();
            DailyRow dailyRow = selectDailyRow(connection, vehicleId, day);
            assertThat(dailyRow).isNotNull();
            assertThat(dailyRow.distanceKm()).isCloseTo(hourA.distanceKm() + hourB.distanceKm(), within(0.001));
            assertThat(dailyRow.movingSecs()).isEqualTo(hourA.movingSecs() + hourB.movingSecs());
            assertThat(dailyRow.idleSecs()).isEqualTo(hourA.idleSecs() + hourB.idleSecs());
            assertThat(dailyRow.maxSpeedKmh()).isEqualTo(Math.max(hourA.maxSpeedKmh(), hourB.maxSpeedKmh()));
        }
    }

    private static void insertStandardTrace(Connection connection, UUID vehicleId, Instant base) throws SQLException {
        insertPosition(connection, vehicleId, base, 4.7100, -74.0700, 40.0, false);
        insertPosition(connection, vehicleId, base.plusSeconds(30), 4.7105, -74.0700, 40.0, false);
        insertPosition(connection, vehicleId, base.plusSeconds(60), 4.7110, -74.0700, 40.0, false);
        insertPosition(connection, vehicleId, base.plusSeconds(90), 4.7115, -74.0700, 0.0, false);
        insertPosition(connection, vehicleId, base.plusSeconds(120), 4.7115, -74.0700, 0.0, false);
        insertPosition(connection, vehicleId, base.plusSeconds(150), 4.7115, -74.0700, 0.0, false);
        insertPosition(connection, vehicleId, base.plusSeconds(180), 4.7115, -74.0700, 0.0, false);
    }

    private static VehicleRollupTask newTask() {
        JdbcTemplate jdbcTemplate = newJdbcTemplate();
        JdbcRollupReader reader = new JdbcRollupReader(jdbcTemplate);
        JdbcHourlyRollupWriter hourlyWriter = new JdbcHourlyRollupWriter(jdbcTemplate);
        JdbcDailyRollupWriter dailyWriter = new JdbcDailyRollupWriter(jdbcTemplate);
        VehicleMotionStreakTracker motionStreakTracker = new VehicleMotionStreakTracker(
            new FleetpulseMotionDetectionProperties(5, 12, Duration.ofSeconds(30))
        );
        FleetpulseRollupsProperties rollupsProperties = new FleetpulseRollupsProperties(Duration.ofHours(24));
        return new VehicleRollupTask(reader, hourlyWriter, dailyWriter, motionStreakTracker, rollupsProperties);
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

    private static void insertPosition(
        Connection connection, UUID vehicleId, Instant recordedAt, double lat, double lon, double speedKmh, boolean ignition
    ) throws SQLException {
        try (
            PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO positions (vehicle_id, recorded_at, location, speed_kmh, ignition) "
                    + "VALUES (?, ?, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography, ?, ?)"
            )
        ) {
            statement.setObject(1, vehicleId);
            statement.setTimestamp(2, Timestamp.from(recordedAt));
            statement.setDouble(3, lon);
            statement.setDouble(4, lat);
            statement.setFloat(5, (float) speedKmh);
            statement.setBoolean(6, ignition);
            statement.executeUpdate();
        }
    }

    private static HourlyRow selectHourlyRow(Connection connection, UUID vehicleId, Instant hour) throws SQLException {
        try (
            PreparedStatement statement = connection.prepareStatement(
                "SELECT distance_km, moving_secs, idle_secs, max_speed_kmh FROM vehicle_hourly WHERE vehicle_id = ? AND hour = ?"
            )
        ) {
            statement.setObject(1, vehicleId);
            statement.setTimestamp(2, Timestamp.from(hour));
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                return toHourlyRow(resultSet);
            }
        }
    }

    private static DailyRow selectDailyRow(Connection connection, UUID vehicleId, LocalDate day) throws SQLException {
        try (
            PreparedStatement statement = connection.prepareStatement(
                "SELECT distance_km, moving_secs, idle_secs, max_speed_kmh FROM vehicle_daily WHERE vehicle_id = ? AND day = ?"
            )
        ) {
            statement.setObject(1, vehicleId);
            statement.setDate(2, Date.valueOf(day));
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                float maxSpeed = resultSet.getFloat("max_speed_kmh");
                Double maxSpeedValue = resultSet.wasNull() ? null : (double) maxSpeed;
                return new DailyRow(
                    resultSet.getDouble("distance_km"), resultSet.getLong("moving_secs"), resultSet.getLong("idle_secs"), maxSpeedValue
                );
            }
        }
    }

    private static HourlyRow toHourlyRow(ResultSet resultSet) throws SQLException {
        float maxSpeed = resultSet.getFloat("max_speed_kmh");
        Double maxSpeedValue = resultSet.wasNull() ? null : (double) maxSpeed;
        return new HourlyRow(
            resultSet.getDouble("distance_km"), resultSet.getLong("moving_secs"), resultSet.getLong("idle_secs"), maxSpeedValue
        );
    }

    private record HourlyRow(double distanceKm, long movingSecs, long idleSecs, Double maxSpeedKmh) {
    }

    private record DailyRow(double distanceKm, long movingSecs, long idleSecs, Double maxSpeedKmh) {
    }
}

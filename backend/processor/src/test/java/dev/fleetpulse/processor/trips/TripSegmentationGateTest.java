package dev.fleetpulse.processor.trips;

import dev.fleetpulse.processor.config.FleetpulseMotionDetectionProperties;
import dev.fleetpulse.processor.config.FleetpulseTripsProperties;
import dev.fleetpulse.processor.telemetry.MutableClock;
import dev.fleetpulse.processor.telemetry.TelemetryActivity;
import dev.fleetpulse.processor.telemetry.VehicleMotionStreakTracker;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

// The scheduled entry point skips a run only when it provably cannot change
// anything (see TripSegmentationTask.segmentWhenNeeded). A spy on the reader
// counts the runs that actually reached the database.
@Testcontainers
class TripSegmentationGateTest {

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

    private final JdbcTemplate jdbcTemplate = new JdbcTemplate(
        new DriverManagerDataSource(postgis.getJdbcUrl(), postgis.getUsername(), postgis.getPassword())
    );

    private MutableClock clock;
    private TelemetryActivity activity;
    private JdbcTripReader reader;
    private TripSegmentationTask task;

    @BeforeEach
    void setUp() {
        Flyway.configure().dataSource(postgis.getJdbcUrl(), postgis.getUsername(), postgis.getPassword()).load().migrate();
        jdbcTemplate.execute("TRUNCATE organizations CASCADE");

        clock = new MutableClock(Instant.now());
        activity = new TelemetryActivity(clock);
        reader = spy(new JdbcTripReader(jdbcTemplate));
        task = new TripSegmentationTask(
            reader,
            new JdbcTripWriter(jdbcTemplate),
            new VehicleMotionStreakTracker(new FleetpulseMotionDetectionProperties(5, 12, Duration.ofSeconds(30))),
            new FleetpulseTripsProperties(Duration.ofMinutes(10)),
            activity,
            clock
        );
    }

    @Test
    void firstRunAfterStartupExecutesEvenWithNoTelemetry() {
        task.segmentWhenNeeded();

        verify(reader, times(1)).loadVehiclesWithThreshold();
    }

    @Test
    void skipsRunsWhileIdleAndNothingIsPending() {
        insertVehicle(60);

        task.segmentWhenNeeded();
        for (int i = 0; i < 4; i++) {
            clock.advance(Duration.ofMinutes(5));
            task.segmentWhenNeeded();
        }

        verify(reader, times(1)).loadVehiclesWithThreshold();
    }

    @Test
    void runsAgainAfterTelemetryIsPersisted() {
        insertVehicle(60);
        task.segmentWhenNeeded();
        clock.advance(Duration.ofMinutes(5));
        task.segmentWhenNeeded();
        verify(reader, times(1)).loadVehiclesWithThreshold();

        activity.recordPersisted(clock.instant().minus(Duration.ofHours(1)));
        clock.advance(Duration.ofMinutes(5));
        task.segmentWhenNeeded();

        verify(reader, times(2)).loadVehiclesWithThreshold();
    }

    @Test
    void keepsRunningWhileATripCanStillCloseByTheProcessingDelayAndThenGoesIdle() {
        UUID vehicleId = insertVehicle(60);
        // Recorded before startup, so only the database seed knows about it.
        // With the 10 minute processing delay, the stop that closes the trip
        // only becomes visible about 2 minutes after the first tick.
        Instant base = clock.instant().minus(Duration.ofSeconds(11 * 60 + 30));
        insertMovingThenStoppedTrace(vehicleId, base);

        task.segmentWhenNeeded();
        assertThat(tripCount(vehicleId)).isZero();

        clock.advance(Duration.ofMinutes(1));
        task.segmentWhenNeeded();
        assertThat(tripCount(vehicleId)).isZero();
        verify(reader, times(2)).loadVehiclesWithThreshold();

        clock.advance(Duration.ofMinutes(1));
        task.segmentWhenNeeded();
        assertThat(tripCount(vehicleId)).isEqualTo(1);

        // The run that closed a trip moved the watermark, so one more run is
        // needed to confirm nothing else closes; only then does it go idle.
        clock.advance(Duration.ofMinutes(1));
        task.segmentWhenNeeded();
        verify(reader, times(4)).loadVehiclesWithThreshold();

        clock.advance(Duration.ofMinutes(1));
        task.segmentWhenNeeded();
        clock.advance(Duration.ofMinutes(5));
        task.segmentWhenNeeded();
        verify(reader, times(4)).loadVehiclesWithThreshold();

        task.segmentAllVehicles();
        assertThat(tripCount(vehicleId)).isEqualTo(1);
    }

    private UUID insertVehicle(int tripStopThresholdSecs) {
        UUID organizationId = UUID.randomUUID();
        UUID vehicleId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO organizations (id, name, created_at, trip_stop_threshold_secs) VALUES (?, ?, ?, ?)",
            organizationId, "Gate Org", Timestamp.from(Instant.now()), tripStopThresholdSecs
        );
        jdbcTemplate.update(
            "INSERT INTO vehicles (id, organization_id, label, created_at) VALUES (?, ?, ?, ?)",
            vehicleId, organizationId, "Truck-Gate", Timestamp.from(Instant.now())
        );
        return vehicleId;
    }

    // Same trace as TripSegmentationEndToEndTest: MOVING confirmed at +30s,
    // STOPPED confirmed at +120s, and a 60s stop threshold reached at +180s.
    private void insertMovingThenStoppedTrace(UUID vehicleId, Instant base) {
        double[] speeds = {40.0, 40.0, 40.0, 0.0, 0.0, 0.0, 0.0};
        for (int i = 0; i < speeds.length; i++) {
            jdbcTemplate.update(
                "INSERT INTO positions (vehicle_id, recorded_at, location, speed_kmh, ignition) "
                    + "VALUES (?, ?, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography, ?, ?)",
                vehicleId, Timestamp.from(base.plusSeconds(30L * i)), -74.07, 4.71, (float) speeds[i], false
            );
        }
    }

    private int tripCount(UUID vehicleId) {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM trips WHERE vehicle_id = ?", Integer.class, vehicleId);
        return count == null ? 0 : count;
    }
}

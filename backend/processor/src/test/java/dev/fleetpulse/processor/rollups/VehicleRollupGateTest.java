package dev.fleetpulse.processor.rollups;

import dev.fleetpulse.processor.config.FleetpulseMotionDetectionProperties;
import dev.fleetpulse.processor.config.FleetpulseRollupsProperties;
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
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

// The scheduled entry point skips a run only when it provably cannot change
// anything (see VehicleRollupTask.recalculateWhenNeeded). A spy on the reader
// counts the runs that actually reached the database.
@Testcontainers
class VehicleRollupGateTest {

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

    // Hour-aligned, inside a closed hour of the default 24h window.
    private static final Instant HOUR_BASE = Instant.now().minus(Duration.ofHours(5)).truncatedTo(ChronoUnit.HOURS);

    private final JdbcTemplate jdbcTemplate = new JdbcTemplate(
        new DriverManagerDataSource(postgis.getJdbcUrl(), postgis.getUsername(), postgis.getPassword())
    );

    private MutableClock clock;
    private TelemetryActivity activity;
    private JdbcRollupReader reader;
    private VehicleRollupTask task;

    @BeforeEach
    void setUp() {
        Flyway.configure().dataSource(postgis.getJdbcUrl(), postgis.getUsername(), postgis.getPassword()).load().migrate();
        jdbcTemplate.execute("TRUNCATE organizations CASCADE");

        // 10 minutes into an hour, so the 15-minute ticks below stay inside
        // it until a test deliberately moves on.
        clock = new MutableClock(HOUR_BASE.plus(Duration.ofHours(2)).plus(Duration.ofMinutes(10)));
        activity = new TelemetryActivity(clock);
        reader = spy(new JdbcRollupReader(jdbcTemplate));
        task = new VehicleRollupTask(
            reader,
            new JdbcHourlyRollupWriter(jdbcTemplate),
            new JdbcDailyRollupWriter(jdbcTemplate),
            new VehicleMotionStreakTracker(new FleetpulseMotionDetectionProperties(5, 12, Duration.ofSeconds(30))),
            new FleetpulseRollupsProperties(Duration.ofHours(24)),
            activity,
            clock
        );
        insertVehicleWithTrace();
    }

    @Test
    void firstRunAfterStartupExecutes() {
        task.recalculateWhenNeeded();

        verify(reader, times(1)).loadVehicles();
        assertThat(hourlyRows()).hasSize(1);
    }

    @Test
    void skipsRunsWithinTheSameHourWhileIdle() {
        task.recalculateWhenNeeded();
        clock.advance(Duration.ofMinutes(15));
        task.recalculateWhenNeeded();
        clock.advance(Duration.ofMinutes(15));
        task.recalculateWhenNeeded();

        verify(reader, times(1)).loadVehicles();
    }

    @Test
    void runsAgainAfterTelemetryIsPersisted() {
        task.recalculateWhenNeeded();
        clock.advance(Duration.ofMinutes(15));
        task.recalculateWhenNeeded();
        verify(reader, times(1)).loadVehicles();

        activity.recordPersisted(clock.instant().minus(Duration.ofMinutes(20)));
        task.recalculateWhenNeeded();

        verify(reader, times(2)).loadVehicles();
    }

    @Test
    void runsOncePerHourWhileTheWindowStillHoldsTelemetry() {
        task.recalculateWhenNeeded();

        clock.advance(Duration.ofHours(1));
        task.recalculateWhenNeeded();
        clock.advance(Duration.ofMinutes(15));
        task.recalculateWhenNeeded();
        verify(reader, times(2)).loadVehicles();

        clock.advance(Duration.ofHours(1));
        task.recalculateWhenNeeded();
        verify(reader, times(3)).loadVehicles();
    }

    @Test
    void stopsRunningOnceTheWindowHasMovedPastTheLastTelemetryAndSkippingChangesNothing() {
        task.recalculateWhenNeeded();

        // windowStart is now after the newest position ever recorded.
        clock.setTo(HOUR_BASE.plus(Duration.ofHours(27)));
        List<Map<String, Object>> hourlyBefore = hourlyRows();
        List<Map<String, Object>> dailyBefore = dailyRows();

        task.recalculateWhenNeeded();
        verify(reader, times(1)).loadVehicles();

        task.recalculateAllVehicles();
        assertThat(hourlyRows()).isEqualTo(hourlyBefore);
        assertThat(dailyRows()).isEqualTo(dailyBefore);
    }

    private void insertVehicleWithTrace() {
        UUID organizationId = UUID.randomUUID();
        UUID vehicleId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO organizations (id, name, created_at) VALUES (?, ?, ?)",
            organizationId, "Gate Org", Timestamp.from(Instant.now())
        );
        jdbcTemplate.update(
            "INSERT INTO vehicles (id, organization_id, label, created_at) VALUES (?, ?, ?, ?)",
            vehicleId, organizationId, "Truck-Gate", Timestamp.from(Instant.now())
        );
        double[] speeds = {40.0, 40.0, 40.0, 0.0, 0.0, 0.0, 0.0};
        double[] lats = {4.7100, 4.7105, 4.7110, 4.7115, 4.7115, 4.7115, 4.7115};
        for (int i = 0; i < speeds.length; i++) {
            jdbcTemplate.update(
                "INSERT INTO positions (vehicle_id, recorded_at, location, speed_kmh, ignition) "
                    + "VALUES (?, ?, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography, ?, ?)",
                vehicleId, Timestamp.from(HOUR_BASE.plusSeconds(30L * i)), -74.07, lats[i], (float) speeds[i], false
            );
        }
    }

    private List<Map<String, Object>> hourlyRows() {
        return jdbcTemplate.queryForList(
            "SELECT vehicle_id, hour, distance_km, moving_secs, idle_secs, max_speed_kmh FROM vehicle_hourly ORDER BY vehicle_id, hour"
        );
    }

    private List<Map<String, Object>> dailyRows() {
        return jdbcTemplate.queryForList(
            "SELECT vehicle_id, day, distance_km, moving_secs, idle_secs, max_speed_kmh FROM vehicle_daily ORDER BY vehicle_id, day"
        );
    }
}

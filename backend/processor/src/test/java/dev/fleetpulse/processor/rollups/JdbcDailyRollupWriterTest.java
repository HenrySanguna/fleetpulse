package dev.fleetpulse.processor.rollups;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

// Fixed, past UTC timestamps (not Instant.now()) so the day boundaries the
// sliding-window scenarios depend on are deterministic.
@Testcontainers
class JdbcDailyRollupWriterTest {

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

    private static final Instant DAY_START = Instant.parse("2026-10-01T00:00:00Z");
    private static final LocalDate DAY = LocalDate.of(2026, 10, 1);

    @Test
    void slidingWindowDoesNotOverwriteADayWithOnlyItsTrailingHours() {
        JdbcTemplate jdbc = migratedJdbcTemplate();
        UUID[] ids = insertVehicle(jdbc);
        for (int hour = 0; hour < 24; hour++) {
            insertHourly(jdbc, ids, DAY_START.plusSeconds(hour * 3600L), 10.0, 100, 50, 30.0 + hour);
        }
        JdbcDailyRollupWriter writer = new JdbcDailyRollupWriter(jdbc);

        writer.recompute(DAY_START, DAY_START.plusSeconds(24 * 3600L));
        assertThat(dailyRow(jdbc, ids[1])).containsEntry("moving_secs", 2400);

        // Window now starts at the last hour of the day: only that hour is in range.
        Instant lastHour = DAY_START.plusSeconds(23 * 3600L);
        writer.recompute(lastHour, lastHour.plusSeconds(24 * 3600L));
        writer.recompute(lastHour, lastHour.plusSeconds(24 * 3600L));

        Map<String, Object> row = dailyRow(jdbc, ids[1]);
        assertThat(((Number) row.get("distance_km")).doubleValue()).isCloseTo(240.0, within(0.01));
        assertThat(row.get("moving_secs")).isEqualTo(2400);
        assertThat(row.get("idle_secs")).isEqualTo(1200);
        assertThat(((Number) row.get("max_speed_kmh")).doubleValue()).isCloseTo(53.0, within(0.01));
    }

    @Test
    void windowStartingMidDayRecomputesTheWholeDay() {
        JdbcTemplate jdbc = migratedJdbcTemplate();
        UUID[] ids = insertVehicle(jdbc);
        for (int hour = 0; hour < 24; hour++) {
            insertHourly(jdbc, ids, DAY_START.plusSeconds(hour * 3600L), 10.0, 100, 50, 40.0);
        }

        new JdbcDailyRollupWriter(jdbc).recompute(DAY_START.plusSeconds(15 * 3600L + 1800L), DAY_START.plusSeconds(30 * 3600L));

        assertThat(((Number) dailyRow(jdbc, ids[1]).get("distance_km")).doubleValue()).isCloseTo(240.0, within(0.01));
    }

    @Test
    void vehicleWithoutHourlyRowsInRangeGetsNoDailyRow() {
        JdbcTemplate jdbc = migratedJdbcTemplate();
        UUID[] ids = insertVehicle(jdbc);

        new JdbcDailyRollupWriter(jdbc).recompute(DAY_START, DAY_START.plusSeconds(24 * 3600L));

        Integer count = jdbc.queryForObject("SELECT count(*) FROM vehicle_daily WHERE vehicle_id = ?", Integer.class, ids[1]);
        assertThat(count).isZero();
    }

    @Test
    void backfillMigrationRepairsACorruptedDailyRow() throws IOException {
        JdbcTemplate jdbc = migratedJdbcTemplate();
        UUID[] ids = insertVehicle(jdbc);
        for (int hour = 0; hour < 24; hour++) {
            insertHourly(jdbc, ids, DAY_START.plusSeconds(hour * 3600L), 10.0, 100, 50, 40.0);
        }
        jdbc.update(
            "INSERT INTO vehicle_daily (vehicle_id, organization_id, day, distance_km, moving_secs, idle_secs, max_speed_kmh, updated_at) "
                + "VALUES (?, ?, ?, 10, 100, 50, 40, now())",
            ids[1], ids[0], java.sql.Date.valueOf(DAY)
        );

        try (InputStream script = getClass().getResourceAsStream("/db/migration/V15__recompute_vehicle_daily_from_hourly.sql")) {
            assertThat(script).isNotNull();
            jdbc.execute(new String(script.readAllBytes(), StandardCharsets.UTF_8));
        }

        Map<String, Object> row = dailyRow(jdbc, ids[1]);
        assertThat(((Number) row.get("distance_km")).doubleValue()).isCloseTo(240.0, within(0.01));
        assertThat(row.get("moving_secs")).isEqualTo(2400);
    }

    private static JdbcTemplate migratedJdbcTemplate() {
        Flyway.configure().dataSource(postgis.getJdbcUrl(), postgis.getUsername(), postgis.getPassword()).load().migrate();
        return new JdbcTemplate(new DriverManagerDataSource(postgis.getJdbcUrl(), postgis.getUsername(), postgis.getPassword()));
    }

    // Returns {organizationId, vehicleId}.
    private static UUID[] insertVehicle(JdbcTemplate jdbc) {
        UUID organizationId = UUID.randomUUID();
        UUID vehicleId = UUID.randomUUID();
        jdbc.update("INSERT INTO organizations (id, name, created_at) VALUES (?, ?, now())", organizationId, "Org " + organizationId);
        jdbc.update("INSERT INTO vehicles (id, organization_id, label, created_at) VALUES (?, ?, ?, now())", vehicleId, organizationId, "V-" + vehicleId);
        return new UUID[] {organizationId, vehicleId};
    }

    private static void insertHourly(
        JdbcTemplate jdbc, UUID[] ids, Instant hour, double distanceKm, int movingSecs, int idleSecs, double maxSpeedKmh
    ) {
        jdbc.update(
            "INSERT INTO vehicle_hourly (vehicle_id, organization_id, hour, distance_km, moving_secs, idle_secs, max_speed_kmh, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, now())",
            ids[1], ids[0], Timestamp.from(hour), distanceKm, movingSecs, idleSecs, maxSpeedKmh
        );
    }

    private static Map<String, Object> dailyRow(JdbcTemplate jdbc, UUID vehicleId) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM vehicle_daily WHERE vehicle_id = ?", vehicleId);
        assertThat(rows).hasSize(1);
        return rows.get(0);
    }
}

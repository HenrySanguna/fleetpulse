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
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// Test 5.7 ("Los gráficos de la consola no consultan positions -- verificar
// en el plan de la consulta servida") and spec.md's own "Requirement:
// Informes servidos desde agregados" scenario. Same pure JDBC + Flyway
// recipe, and the same EXPLAIN-based proof technique, as
// PgPartmanPartitionMaintenanceTest.historyQueryForOneVehicleAndDateRangeNeverDoesASequentialScan()
// -- that test proves a positions query DOES hit the partitioned table (by
// design); this one proves the activity report's own two queries (from
// JdbcActivityReportRepository, backend/api module -- duplicated here
// verbatim rather than shared, the same cross-module duplication
// PgPartmanPartitionMaintenanceTest's own explainHistoryQuery() already
// accepts, since domain cannot depend on api) NEVER reference `positions`
// at all, even under EXPLAIN ANALYZE's real execution. `positions` itself is
// seeded with real rows for the SAME vehicle/organization specifically so a
// query that accidentally joined or fell back to it would have real data to
// scan -- an empty table proves nothing.
@Testcontainers
class ActivityReportQueryPlanTest {

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
    void dailyRollupAndTripsQueriesForTheActivityReportNeverScanPositions() throws Exception {
        migrate();

        try (Connection connection = connect()) {
            UUID organizationId = insertOrganization(connection, "Acme Activity Report Plan Org");
            UUID vehicleId = insertVehicle(connection, organizationId, "Truck-ARP-1");
            seedPositions(connection, vehicleId);
            seedDailyRows(connection, organizationId, vehicleId);
            seedTrips(connection, organizationId, vehicleId);

            try (Statement analyze = connection.createStatement()) {
                analyze.execute("ANALYZE positions, vehicle_daily, trips");
            }

            String dailyPlan = explainDailyQuery(connection, organizationId, vehicleId);
            assertThat(dailyPlan)
                .withFailMessage("Expected the daily rollup query to never reference positions, got plan:%n%s", dailyPlan)
                .doesNotContainIgnoringCase("positions");

            String tripsPlan = explainTripsQuery(connection, organizationId, vehicleId);
            assertThat(tripsPlan)
                .withFailMessage("Expected the trips query to never reference positions, got plan:%n%s", tripsPlan)
                .doesNotContainIgnoringCase("positions");
        }
    }

    // Mirrors JdbcActivityReportRepository.SELECT_DAILY_SQL exactly.
    private static String explainDailyQuery(Connection connection, UUID organizationId, UUID vehicleId) throws SQLException {
        return explain(
            connection,
            "SELECT day, distance_km, moving_secs, idle_secs, max_speed_kmh "
                + "FROM vehicle_daily "
                + "WHERE organization_id = ? AND vehicle_id = ? AND day BETWEEN ? AND ? "
                + "ORDER BY day ASC",
            organizationId, vehicleId, Date.valueOf(LocalDate.now().minusDays(6)), Date.valueOf(LocalDate.now())
        );
    }

    // Mirrors JdbcActivityReportRepository.SELECT_TRIPS_SQL exactly.
    private static String explainTripsQuery(Connection connection, UUID organizationId, UUID vehicleId) throws SQLException {
        return explain(
            connection,
            "SELECT id, started_at, ended_at, distance_km, duration_secs, idle_secs, max_speed_kmh "
                + "FROM trips "
                + "WHERE organization_id = ? AND vehicle_id = ? AND started_at >= ? AND started_at <= ? "
                + "ORDER BY started_at DESC",
            organizationId, vehicleId, Timestamp.from(Instant.now().minusSeconds(7 * 86400)), Timestamp.from(Instant.now())
        );
    }

    private static String explain(Connection connection, String sql, Object... params) throws SQLException {
        StringBuilder plan = new StringBuilder();
        try (PreparedStatement statement = connection.prepareStatement("EXPLAIN (ANALYZE, FORMAT TEXT) " + sql)) {
            for (int i = 0; i < params.length; i++) {
                statement.setObject(i + 1, params[i]);
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    plan.append(resultSet.getString(1)).append('\n');
                }
            }
        }
        return plan.toString();
    }

    private static void seedPositions(Connection connection, UUID vehicleId) throws SQLException {
        try (
            PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO positions (vehicle_id, recorded_at, location, speed_kmh) "
                    + "SELECT ?, now() - (n || ' minutes')::interval, "
                    + "ST_SetSRID(ST_MakePoint(-74.0 + random(), 4.7 + random()), 4326)::geography, random() * 100 "
                    + "FROM generate_series(1, 200) AS n"
            )
        ) {
            statement.setObject(1, vehicleId);
            statement.executeUpdate();
        }
    }

    private static void seedDailyRows(Connection connection, UUID organizationId, UUID vehicleId) throws SQLException {
        try (
            PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO vehicle_daily (vehicle_id, organization_id, day, distance_km, moving_secs, idle_secs, max_speed_kmh, updated_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
            )
        ) {
            for (int i = 0; i < 5; i++) {
                statement.setObject(1, vehicleId);
                statement.setObject(2, organizationId);
                statement.setDate(3, Date.valueOf(LocalDate.now().minusDays(i)));
                statement.setFloat(4, 50.0f + i);
                statement.setInt(5, 1800);
                statement.setInt(6, 300);
                statement.setFloat(7, 80.0f);
                statement.setTimestamp(8, Timestamp.from(Instant.now()));
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private static void seedTrips(Connection connection, UUID organizationId, UUID vehicleId) throws SQLException {
        try (
            PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO trips (id, organization_id, vehicle_id, started_at, ended_at, distance_km, duration_secs, idle_secs, "
                    + "max_speed_kmh, avg_speed_kmh, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
            )
        ) {
            for (int i = 0; i < 5; i++) {
                Instant startedAt = Instant.now().minusSeconds((i + 1) * 3600L);
                statement.setObject(1, UUID.randomUUID());
                statement.setObject(2, organizationId);
                statement.setObject(3, vehicleId);
                statement.setTimestamp(4, Timestamp.from(startedAt));
                statement.setTimestamp(5, Timestamp.from(startedAt.plusSeconds(1800)));
                statement.setFloat(6, 20.0f);
                statement.setInt(7, 1800);
                statement.setInt(8, 200);
                statement.setFloat(9, 70.0f);
                statement.setFloat(10, 25.0f);
                statement.setTimestamp(11, Timestamp.from(Instant.now()));
                statement.addBatch();
            }
            statement.executeBatch();
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

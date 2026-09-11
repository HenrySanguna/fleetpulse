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
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// Task 1.3: pg_partman create_parent()/retention config for `positions`
// (migration V6). Proves the operational partition lifecycle pg_partman
// owns once wired in -- the DoD item "las particiones de la semana
// siguiente existen antes de que empiece esa semana" is what create_parent's
// premake already guarantees immediately at migration time, before any
// @Scheduled run ever executes; keeping that true forever as weeks keep
// passing is PartitionMaintenanceTask's job (processor module, task 1.5),
// not this migration's -- this test only proves the maintenance procedure
// itself runs cleanly, not the Java wiring that invokes it on a schedule.
//
// Test 6.8 (history-by-vehicle-and-date-range query plan) lives here too,
// not in its own file: it needs the exact same pg_partman-configured
// `positions` container and migration state as the tests above, and a
// second @Testcontainers class would only duplicate the container/helper
// boilerplate for no test-isolation benefit.
@Testcontainers
class PgPartmanPartitionMaintenanceTest {

    private static final int NOISE_VEHICLE_COUNT = 5;
    private static final int POSITIONS_PER_VEHICLE = 500;

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
    void positionsHasAPartitionCoveringNextWeekImmediatelyAfterMigration() throws Exception {
        migrate();

        try (Connection connection = connect()) {
            Instant furthestPartitionStart = furthestPartitionLowerBound(connection);
            Instant startOfNextWeek = Instant.now().plusSeconds(7L * 24 * 3600);

            assertThat(furthestPartitionStart)
                .withFailMessage(
                    "Expected a positions partition starting at or after %s (next week), furthest created starts at %s",
                    startOfNextWeek, furthestPartitionStart
                )
                .isAfterOrEqualTo(startOfNextWeek);
        }
    }

    @Test
    void positionsPartitionConfigHasAConfigurableRetentionThatDropsOldPartitions() throws Exception {
        migrate();

        try (Connection connection = connect()) {
            try (
                PreparedStatement statement = connection.prepareStatement(
                    "SELECT retention, retention_keep_table FROM partman.part_config WHERE parent_table = 'public.positions'"
                );
                ResultSet resultSet = statement.executeQuery()
            ) {
                assertThat(resultSet.next()).isTrue();
                assertThat(resultSet.getString("retention")).isNotBlank();
                // Old partitions must be dropped outright, not merely detached
                // and kept around -- otherwise "retention" would just rename
                // the storage problem instead of solving it.
                assertThat(resultSet.getBoolean("retention_keep_table")).isFalse();
            }

            // "Retencion configurable" (task 1.3) means the value lives in
            // pg_partman's own config table, adjustable with a plain UPDATE --
            // not a constant hardcoded in application code.
            try (
                PreparedStatement update = connection.prepareStatement(
                    "UPDATE partman.part_config SET retention = '30 days' WHERE parent_table = 'public.positions'"
                )
            ) {
                assertThat(update.executeUpdate()).isEqualTo(1);
            }
        }
    }

    @Test
    void positionsAcceptsInsertsNowThatPgPartmanHasCreatedPartitions() throws Exception {
        migrate();

        try (Connection connection = connect()) {
            UUID organizationId = insertOrganization(connection, "Acme Partman Org");
            UUID vehicleId = insertVehicle(connection, organizationId, "Truck-PM-1");

            try (
                PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO positions (vehicle_id, recorded_at, location) "
                        + "VALUES (?, ?, ST_SetSRID(ST_MakePoint(-74.049, 4.704), 4326)::geography)"
                )
            ) {
                statement.setObject(1, vehicleId);
                statement.setTimestamp(2, Timestamp.from(Instant.now()));
                assertThat(statement.executeUpdate()).isEqualTo(1);
            }
        }
    }

    @Test
    void runMaintenanceProcRunsCleanlyWithoutDroppingTheFurthestPremadePartition() throws Exception {
        migrate();

        try (Connection connection = connect()) {
            Instant beforeMaintenance = furthestPartitionLowerBound(connection);

            try (Statement statement = connection.createStatement()) {
                statement.execute("CALL partman.run_maintenance_proc()");
            }

            Instant afterMaintenance = furthestPartitionLowerBound(connection);
            assertThat(afterMaintenance).isAfterOrEqualTo(beforeMaintenance);
        }
    }

    // Test 6.8: proven against real data volume (five vehicles x 500
    // positions, most of it noise for other vehicles) because a handful of
    // rows in a fresh table is exactly the case where Postgres' planner can
    // legitimately pick a seq scan regardless of available indexes -- a
    // trivial dataset would not actually prove anything.
    @Test
    void historyQueryForOneVehicleAndDateRangeNeverDoesASequentialScan() throws Exception {
        migrate();

        try (Connection connection = connect()) {
            UUID organizationId = insertOrganization(connection, "Acme History Org");
            UUID targetVehicleId = insertVehicle(connection, organizationId, "Truck-HQ-1");
            seedPositions(connection, targetVehicleId);
            for (int i = 1; i < NOISE_VEHICLE_COUNT; i++) {
                seedPositions(connection, insertVehicle(connection, organizationId, "Truck-HQ-noise-" + i));
            }

            try (Statement analyze = connection.createStatement()) {
                analyze.execute("ANALYZE positions");
            }

            String plan = explainHistoryQuery(connection, targetVehicleId);

            assertThat(plan)
                .withFailMessage("Expected no sequential scan on positions, got plan:%n%s", plan)
                .doesNotContainIgnoringCase("Seq Scan");
            assertThat(plan)
                .withFailMessage("Expected partition pruning to remove unrelated partitions, got plan:%n%s", plan)
                .containsIgnoringCase("Subplans Removed");
        }
    }

    private static void seedPositions(Connection connection, UUID vehicleId) throws SQLException {
        try (
            PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO positions (vehicle_id, recorded_at, location, speed_kmh) "
                    + "SELECT ?, now() - (n || ' minutes')::interval, "
                    + "ST_SetSRID(ST_MakePoint(-74.0 + random(), 4.7 + random()), 4326)::geography, random() * 100 "
                    + "FROM generate_series(1, ?) AS n"
            )
        ) {
            statement.setObject(1, vehicleId);
            statement.setInt(2, POSITIONS_PER_VEHICLE);
            statement.executeUpdate();
        }
    }

    private static String explainHistoryQuery(Connection connection, UUID vehicleId) throws SQLException {
        StringBuilder plan = new StringBuilder();
        try (
            PreparedStatement statement = connection.prepareStatement(
                "EXPLAIN (ANALYZE, FORMAT TEXT) "
                    + "SELECT vehicle_id, recorded_at, speed_kmh FROM positions "
                    + "WHERE vehicle_id = ? AND recorded_at BETWEEN now() - interval '2 hours' AND now() "
                    + "ORDER BY recorded_at"
            )
        ) {
            statement.setObject(1, vehicleId);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    plan.append(resultSet.getString(1)).append('\n');
                }
            }
        }
        return plan.toString();
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

    private static Instant furthestPartitionLowerBound(Connection connection) throws SQLException {
        try (
            PreparedStatement statement = connection.prepareStatement(
                "SELECT max((split_part(pg_get_expr(c.relpartbound, c.oid), '''', 2))::timestamptz) AS furthest_start "
                    + "FROM pg_inherits i "
                    + "JOIN pg_class c ON c.oid = i.inhrelid "
                    + "JOIN pg_class p ON p.oid = i.inhparent "
                    + "WHERE p.relname = 'positions' "
                    + "AND pg_get_expr(c.relpartbound, c.oid) LIKE 'FOR VALUES FROM%'"
            );
            ResultSet resultSet = statement.executeQuery()
        ) {
            assertThat(resultSet.next()).isTrue();
            Timestamp furthest = resultSet.getTimestamp("furthest_start");
            assertThat(furthest).withFailMessage("No ranged positions partitions found").isNotNull();
            return furthest.toInstant();
        }
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

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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Pure JDBC + Flyway, no Spring context, same recipe as FlywayMigrationTest
// and FleetAuthSchemaConstraintTest: proves the positions/vehicle_state
// schema itself (partitioning strategy, primary key, index access methods,
// motion_state domain) rather than any Java code, since this work unit is
// schema-only -- positions has no partitions yet (pg_partman wires those in
// the next work unit), so it cannot accept inserts until then.
@Testcontainers
class TelemetrySchemaTest {

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
    void positionsIsRangePartitionedOnRecordedAtWithCompositePrimaryKey() throws Exception {
        migrate();

        try (Connection connection = connect()) {
            assertThat(partitionStrategy(connection, "positions")).isEqualTo("RANGE");
            assertThat(primaryKeyColumns(connection, "positions")).containsExactly("vehicle_id", "recorded_at");
        }
    }

    @Test
    void vehicleStateHasSingleColumnPrimaryKeyOnVehicleId() throws Exception {
        migrate();

        try (Connection connection = connect()) {
            assertThat(primaryKeyColumns(connection, "vehicle_state")).containsExactly("vehicle_id");
        }
    }

    @Test
    void positionsHasBrinIndexOnRecordedAtAndGistIndexOnLocation() throws Exception {
        migrate();

        try (Connection connection = connect()) {
            List<String> indexDefinitions = indexDefinitions(connection, "positions");

            assertThat(indexDefinitions)
                .anyMatch(def -> def.contains("USING brin (recorded_at)"));
            assertThat(indexDefinitions)
                .anyMatch(def -> def.contains("USING gist (location)"));
        }
    }

    @Test
    void vehicleStateTracksLastKnownPositionMotionStateAndOnlineFlag() throws Exception {
        migrate();

        try (Connection connection = connect()) {
            UUID organizationId = insertOrganization(connection, "Acme Telemetry Org");
            UUID vehicleId = insertVehicle(connection, organizationId, "Truck-TG-1");

            insertVehicleState(connection, vehicleId, 4.704, -74.049, "MOVING", true);

            try (
                PreparedStatement statement = connection.prepareStatement(
                    "SELECT motion_state, online, ST_X(location::geometry) AS lon, ST_Y(location::geometry) AS lat "
                        + "FROM vehicle_state WHERE vehicle_id = ?"
                )
            ) {
                statement.setObject(1, vehicleId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    assertThat(resultSet.next()).isTrue();
                    assertThat(resultSet.getString("motion_state")).isEqualTo("MOVING");
                    assertThat(resultSet.getBoolean("online")).isTrue();
                    assertThat(resultSet.getDouble("lat")).isEqualTo(4.704);
                    assertThat(resultSet.getDouble("lon")).isEqualTo(-74.049);
                }
            }
        }
    }

    @Test
    void vehicleStateRejectsMotionStateOutsideGeoCoreEnum() throws Exception {
        migrate();

        try (Connection connection = connect()) {
            UUID organizationId = insertOrganization(connection, "Acme Telemetry Org 2");
            UUID vehicleId = insertVehicle(connection, organizationId, "Truck-TG-2");

            assertThatThrownBy(() -> insertVehicleState(connection, vehicleId, 0, 0, "CRUISING", false))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("chk_vehicle_state_motion_state");
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

    private static String partitionStrategy(Connection connection, String tableName) throws SQLException {
        try (
            PreparedStatement statement = connection.prepareStatement(
                "SELECT CASE pt.partstrat WHEN 'r' THEN 'RANGE' WHEN 'l' THEN 'LIST' WHEN 'h' THEN 'HASH' END AS strategy "
                    + "FROM pg_partitioned_table pt JOIN pg_class c ON c.oid = pt.partrelid WHERE c.relname = ?"
            )
        ) {
            statement.setString(1, tableName);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertThat(resultSet.next())
                    .withFailMessage("Table '%s' is not declared as a partitioned table", tableName)
                    .isTrue();
                return resultSet.getString("strategy");
            }
        }
    }

    private static List<String> primaryKeyColumns(Connection connection, String tableName) throws SQLException {
        List<String> columns = new ArrayList<>();
        try (
            PreparedStatement statement = connection.prepareStatement(
                "SELECT kcu.column_name FROM information_schema.table_constraints tc "
                    + "JOIN information_schema.key_column_usage kcu "
                    + "  ON tc.constraint_name = kcu.constraint_name AND tc.table_schema = kcu.table_schema "
                    + "WHERE tc.table_name = ? AND tc.constraint_type = 'PRIMARY KEY' "
                    + "ORDER BY kcu.ordinal_position"
            )
        ) {
            statement.setString(1, tableName);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    columns.add(resultSet.getString("column_name"));
                }
            }
        }
        return columns;
    }

    private static List<String> indexDefinitions(Connection connection, String tableName) throws SQLException {
        List<String> definitions = new ArrayList<>();
        try (
            Statement statement = connection.createStatement();
            ResultSet resultSet = statement
                .executeQuery("SELECT indexdef FROM pg_indexes WHERE tablename = '" + tableName + "'")
        ) {
            while (resultSet.next()) {
                definitions.add(resultSet.getString("indexdef"));
            }
        }
        return definitions;
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

    private static void insertVehicleState(
        Connection connection,
        UUID vehicleId,
        double lat,
        double lon,
        String motionState,
        boolean online
    ) throws SQLException {
        try (
            PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO vehicle_state (vehicle_id, location, recorded_at, motion_state, online) "
                    + "VALUES (?, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography, ?, ?, ?)"
            )
        ) {
            statement.setObject(1, vehicleId);
            statement.setDouble(2, lon);
            statement.setDouble(3, lat);
            statement.setTimestamp(4, Timestamp.from(Instant.now()));
            statement.setString(5, motionState);
            statement.setBoolean(6, online);
            statement.executeUpdate();
        }
    }
}

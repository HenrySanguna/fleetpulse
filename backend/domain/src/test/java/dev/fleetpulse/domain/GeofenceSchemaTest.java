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

// Pure JDBC + Flyway, no Spring context, same recipe as TelemetrySchemaTest
// and FleetAuthSchemaConstraintTest: proves the geofences/vehicle_fence_state
// schema itself (GiST index, rule/dwell_secs constraints, composite primary
// key, and the "a circle is stored as a buffered polygon" convention task
// 1.3 commits to) rather than any Java code -- this work unit is schema-only,
// no evaluation/CRUD code exists yet to exercise instead.
@Testcontainers
class GeofenceSchemaTest {

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
    void geofencesHasGistIndexOnArea() throws Exception {
        migrate();

        try (Connection connection = connect()) {
            List<String> indexDefinitions = indexDefinitions(connection, "geofences");

            assertThat(indexDefinitions).anyMatch(def -> def.contains("USING gist (area)"));
        }
    }

    @Test
    void vehicleFenceStateHasCompositePrimaryKeyOnVehicleAndGeofence() throws Exception {
        migrate();

        try (Connection connection = connect()) {
            assertThat(primaryKeyColumns(connection, "vehicle_fence_state"))
                .containsExactly("vehicle_id", "geofence_id");
        }
    }

    @Test
    void geofencesRejectsRuleOutsideTheThreeAllowedValues() throws Exception {
        migrate();

        try (Connection connection = connect()) {
            UUID organizationId = insertOrganization(connection, "Acme Geofencing Org");

            assertThatThrownBy(() -> insertPolygonGeofence(connection, organizationId, "Depot", "on_arrival", null))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("chk_geofences_rule");
        }
    }

    @Test
    void geofencesRequiresPositiveDwellSecsOnlyForOnDwellRule() throws Exception {
        migrate();

        try (Connection connection = connect()) {
            UUID organizationId = insertOrganization(connection, "Acme Geofencing Org 2");

            assertThatThrownBy(() -> insertPolygonGeofence(connection, organizationId, "Depot", "on_dwell", null))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("chk_geofences_dwell_secs");

            assertThatThrownBy(() -> insertPolygonGeofence(connection, organizationId, "Depot", "on_enter", 300))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("chk_geofences_dwell_secs");

            assertThatThrownBy(() -> insertPolygonGeofence(connection, organizationId, "Depot", "on_dwell", 0))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("chk_geofences_dwell_secs");
        }
    }

    @Test
    void geofencesAcceptsAWellFormedPolygonWithOnEnterRule() throws Exception {
        migrate();

        try (Connection connection = connect()) {
            UUID organizationId = insertOrganization(connection, "Acme Geofencing Org 3");
            UUID geofenceId = insertPolygonGeofence(connection, organizationId, "Depot", "on_enter", null);

            try (
                PreparedStatement statement = connection
                    .prepareStatement("SELECT name, rule, is_active FROM geofences WHERE id = ?")
            ) {
                statement.setObject(1, geofenceId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    assertThat(resultSet.next()).isTrue();
                    assertThat(resultSet.getString("name")).isEqualTo("Depot");
                    assertThat(resultSet.getString("rule")).isEqualTo("on_enter");
                    assertThat(resultSet.getBoolean("is_active")).isTrue();
                }
            }
        }
    }

    // Task 1.3: a circular geofence has no dedicated storage shape -- it is
    // written as the ST_Buffer polygon of its center, so ST_Contains against
    // `area` is the ONLY evaluation path the future evaluator (task 2.1)
    // ever needs, regardless of whether the geofence was originally drawn as
    // a circle or a polygon. This proves that convention end-to-end at the
    // schema/SQL level: buffer a center point by a radius, store it exactly
    // like any other polygon geofence, and confirm containment with the same
    // ST_Contains predicate a hand-drawn polygon would use.
    @Test
    void aCircularGeofenceStoredAsABufferedPolygonEvaluatesWithTheSamePredicateAsAPolygon() throws Exception {
        migrate();

        try (Connection connection = connect()) {
            UUID organizationId = insertOrganization(connection, "Acme Geofencing Org 4");
            UUID geofenceId = UUID.randomUUID();

            // Center at (lon -74.0721, lat 4.7110), 200m radius.
            try (
                PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO geofences (id, organization_id, name, area, rule, created_at) "
                        + "VALUES (?, ?, ?, ST_Buffer(ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography, ?), ?, ?)"
                )
            ) {
                statement.setObject(1, geofenceId);
                statement.setObject(2, organizationId);
                statement.setString(3, "Circular Yard");
                statement.setDouble(4, -74.0721);
                statement.setDouble(5, 4.7110);
                statement.setDouble(6, 200.0);
                statement.setString(7, "on_enter");
                statement.setTimestamp(8, Timestamp.from(Instant.now()));
                statement.executeUpdate();
            }

            assertThat(pointIsContained(connection, geofenceId, -74.0721, 4.7110)).isTrue();
            assertThat(pointIsContained(connection, geofenceId, -73.0, 4.7110)).isFalse();
        }
    }

    private static boolean pointIsContained(Connection connection, UUID geofenceId, double lon, double lat) throws SQLException {
        try (
            PreparedStatement statement = connection.prepareStatement(
                "SELECT ST_Contains(area::geometry, ST_SetSRID(ST_MakePoint(?, ?), 4326)) AS is_contained "
                    + "FROM geofences WHERE id = ?"
            )
        ) {
            statement.setDouble(1, lon);
            statement.setDouble(2, lat);
            statement.setObject(3, geofenceId);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).isTrue();
                return resultSet.getBoolean("is_contained");
            }
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

    // A simple closed square around (lon -74.07, lat 4.71), reused by every
    // test that only cares about constraint behavior, not geometry shape.
    private static UUID insertPolygonGeofence(
        Connection connection,
        UUID organizationId,
        String name,
        String rule,
        Integer dwellSecs
    ) throws SQLException {
        UUID id = UUID.randomUUID();
        try (
            PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO geofences (id, organization_id, name, area, rule, dwell_secs, created_at) "
                    + "VALUES (?, ?, ?, ST_GeomFromText(?, 4326)::geography, ?, ?, ?)"
            )
        ) {
            statement.setObject(1, id);
            statement.setObject(2, organizationId);
            statement.setString(3, name);
            statement.setString(4, "POLYGON((-74.08 4.70, -74.06 4.70, -74.06 4.72, -74.08 4.72, -74.08 4.70))");
            statement.setString(5, rule);
            if (dwellSecs == null) {
                statement.setNull(6, java.sql.Types.INTEGER);
            } else {
                statement.setInt(6, dwellSecs);
            }
            statement.setTimestamp(7, Timestamp.from(Instant.now()));
            statement.executeUpdate();
        }
        return id;
    }
}

package dev.fleetpulse.processor.geofencing;

import dev.fleetpulse.geocore.FenceTransition;
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
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

// Tasks 2.1-2.3 wired against a real PostGIS database (same Dockerfile image
// domain's/WU1's schema tests use): a JdbcTemplate-level harness inserting
// rows directly into `geofences`/`vehicle_fence_state`, calling
// GeofenceEvaluator.evaluate() directly -- no MQTT/processor pipeline wiring
// needed to prove the query + set comparison itself (that wiring is WU4's
// concern, per the finalized work-unit forecast).
@Testcontainers
class GeofenceEvaluatorTest {

    // A point inside geofence A's square [-74.08, -74.06] x [4.70, 4.72] and
    // also inside geofence B's overlapping square [-74.075, -74.055] x
    // [4.702, 4.722] -- used by the overlap test (6.5).
    private static final double POINT_LON = -74.068;
    private static final double POINT_LAT = 4.711;
    private static final double OUTSIDE_LON = -73.0;
    private static final double OUTSIDE_LAT = 4.711;

    private static final int NOISE_GEOFENCE_COUNT = 500;

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
    void evaluateReportsEnteredForAGeofenceNewlyContainingThePointWithNoPriorState() throws Exception {
        migrate();
        GeofenceEvaluator evaluator = newEvaluator();

        try (Connection connection = connect()) {
            UUID organizationId = insertOrganization(connection, "Acme Eval Org 1");
            UUID vehicleId = insertVehicle(connection, organizationId, "Truck-EV-1");
            UUID geofenceId = insertSquareGeofence(connection, organizationId, "Depot", -74.07, 4.71, 0.01, "on_enter", true);

            List<GeofenceTransitionResult> results = evaluator.evaluate(vehicleId, organizationId, POINT_LAT, POINT_LON);

            assertThat(results).containsExactly(new GeofenceTransitionResult(geofenceId, FenceTransition.ENTERED));
        }
    }

    @Test
    void evaluateReportsExitedWhenThePointLeavesAGeofenceThatWasPreviouslyInside() throws Exception {
        migrate();
        GeofenceEvaluator evaluator = newEvaluator();

        try (Connection connection = connect()) {
            UUID organizationId = insertOrganization(connection, "Acme Eval Org 2");
            UUID vehicleId = insertVehicle(connection, organizationId, "Truck-EV-2");
            UUID geofenceId = insertSquareGeofence(connection, organizationId, "Depot", -74.07, 4.71, 0.01, "on_exit", true);
            insertVehicleFenceState(connection, vehicleId, geofenceId, true, Instant.now().minusSeconds(600));

            List<GeofenceTransitionResult> results = evaluator.evaluate(vehicleId, organizationId, OUTSIDE_LAT, OUTSIDE_LON);

            assertThat(results).containsExactly(new GeofenceTransitionResult(geofenceId, FenceTransition.EXITED));
        }
    }

    // Documents the design decision in GeofenceEvaluator's class comment: a
    // deactivated geofence is excluded from the containment query, so a
    // vehicle that was previously inside it still reports EXITED rather than
    // leaving a stale is_inside = true row unexplained.
    @Test
    void evaluateReportsExitedWhenAPreviouslyInsideGeofenceHasBeenDeactivated() throws Exception {
        migrate();
        GeofenceEvaluator evaluator = newEvaluator();

        try (Connection connection = connect()) {
            UUID organizationId = insertOrganization(connection, "Acme Eval Org 3");
            UUID vehicleId = insertVehicle(connection, organizationId, "Truck-EV-3");
            UUID geofenceId = insertSquareGeofence(connection, organizationId, "Depot", -74.07, 4.71, 0.01, "on_exit", false);
            insertVehicleFenceState(connection, vehicleId, geofenceId, true, Instant.now().minusSeconds(600));

            List<GeofenceTransitionResult> results = evaluator.evaluate(vehicleId, organizationId, POINT_LAT, POINT_LON);

            assertThat(results).containsExactly(new GeofenceTransitionResult(geofenceId, FenceTransition.EXITED));
        }
    }

    @Test
    void evaluateReportsNothingWhenMembershipIsUnchanged() throws Exception {
        migrate();
        GeofenceEvaluator evaluator = newEvaluator();

        try (Connection connection = connect()) {
            UUID organizationId = insertOrganization(connection, "Acme Eval Org 4");
            UUID vehicleId = insertVehicle(connection, organizationId, "Truck-EV-4");
            UUID insideGeofenceId = insertSquareGeofence(connection, organizationId, "Depot", -74.07, 4.71, 0.01, "on_enter", true);
            insertVehicleFenceState(connection, vehicleId, insideGeofenceId, true, Instant.now().minusSeconds(600));

            List<GeofenceTransitionResult> stillInside = evaluator.evaluate(vehicleId, organizationId, POINT_LAT, POINT_LON);
            assertThat(stillInside).isEmpty();

            UUID otherVehicleId = insertVehicle(connection, organizationId, "Truck-EV-4b");
            List<GeofenceTransitionResult> stillOutside = evaluator.evaluate(otherVehicleId, organizationId, OUTSIDE_LAT, OUTSIDE_LON);
            assertThat(stillOutside).isEmpty();
        }
    }

    // Test 6.5: two active, overlapping geofences -- the query (task 2.1)
    // returns both containing IDs in one pass, and the set comparison (task
    // 2.2/2.3) emits an independent ENTERED transition per geofence, not a
    // single merged result.
    @Test
    void evaluateReportsAnEnteredTransitionForEachOfTwoOverlappingGeofencesEnteredSimultaneously() throws Exception {
        migrate();
        GeofenceEvaluator evaluator = newEvaluator();

        try (Connection connection = connect()) {
            UUID organizationId = insertOrganization(connection, "Acme Eval Org 5");
            UUID vehicleId = insertVehicle(connection, organizationId, "Truck-EV-5");
            UUID geofenceA = insertSquareGeofence(connection, organizationId, "Zone A", -74.07, 4.71, 0.01, "on_enter", true);
            UUID geofenceB = insertSquareGeofence(connection, organizationId, "Zone B", -74.065, 4.712, 0.01, "on_enter", true);

            List<GeofenceTransitionResult> results = evaluator.evaluate(vehicleId, organizationId, POINT_LAT, POINT_LON);

            assertThat(results)
                .extracting(GeofenceTransitionResult::geofenceId, GeofenceTransitionResult::transition)
                .containsExactlyInAnyOrder(
                    tuple(geofenceA, FenceTransition.ENTERED),
                    tuple(geofenceB, FenceTransition.ENTERED)
                );
        }
    }

    // Test 6.9: proven against real data volume (500 scattered noise
    // geofences plus the target) because a handful of rows in a fresh table
    // is exactly the case where Postgres' planner can legitimately pick a
    // sequential scan regardless of available indexes -- a trivial dataset
    // would not actually prove anything (same reasoning as
    // PgPartmanPartitionMaintenanceTest's history-query plan test, change
    // 03-add-telemetry-ingest).
    @Test
    void containingGeofenceQueryUsesTheGistIndexWithNoSequentialScan() throws Exception {
        migrate();

        try (Connection connection = connect()) {
            UUID organizationId = insertOrganization(connection, "Acme Plan Org");
            insertSquareGeofence(connection, organizationId, "Target", -74.07, 4.71, 0.01, "on_enter", true);
            seedNoiseGeofences(connection, organizationId, NOISE_GEOFENCE_COUNT);

            try (Statement analyze = connection.createStatement()) {
                analyze.execute("ANALYZE geofences");
            }

            String plan = explainContainingGeofencesQuery(connection, organizationId, POINT_LON, POINT_LAT);

            assertThat(plan)
                .withFailMessage("Expected no sequential scan on geofences, got plan:%n%s", plan)
                .doesNotContainIgnoringCase("Seq Scan");
            assertThat(plan)
                .withFailMessage("Expected the GiST index on geofences.area to be used, got plan:%n%s", plan)
                .containsIgnoringCase("idx_geofences_area");
        }
    }

    private static void seedNoiseGeofences(Connection connection, UUID organizationId, int count) throws SQLException {
        try (
            PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO geofences (id, organization_id, name, area, rule, is_active, created_at) "
                    + "VALUES (?, ?, ?, ST_Buffer(ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography, 50), 'on_enter', true, ?)"
            )
        ) {
            for (int i = 0; i < count; i++) {
                statement.setObject(1, UUID.randomUUID());
                statement.setObject(2, organizationId);
                statement.setString(3, "Noise-" + i);
                // Scattered across roughly a 1deg x 1deg box, far enough from
                // POINT_LON/POINT_LAT that almost none of these contain it.
                statement.setDouble(4, -76.0 + Math.random());
                statement.setDouble(5, 4.0 + Math.random());
                statement.setTimestamp(6, Timestamp.from(Instant.now()));
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private static String explainContainingGeofencesQuery(
        Connection connection, UUID organizationId, double lon, double lat
    ) throws SQLException {
        StringBuilder plan = new StringBuilder();
        try (
            PreparedStatement statement = connection
                .prepareStatement("EXPLAIN (ANALYZE, FORMAT TEXT) " + GeofenceEvaluator.CONTAINING_GEOFENCE_IDS_SQL)
        ) {
            statement.setObject(1, organizationId);
            statement.setDouble(2, lon);
            statement.setDouble(3, lat);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    plan.append(resultSet.getString(1)).append('\n');
                }
            }
        }
        return plan.toString();
    }

    private static GeofenceEvaluator newEvaluator() {
        return new GeofenceEvaluator(newJdbcTemplate());
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

    // A closed square of side 2*halfWidthDegrees centered on (centerLon,
    // centerLat), stored exactly like GeofenceSchemaTest's polygon fixture.
    private static UUID insertSquareGeofence(
        Connection connection,
        UUID organizationId,
        String name,
        double centerLon,
        double centerLat,
        double halfWidthDegrees,
        String rule,
        boolean isActive
    ) throws SQLException {
        UUID id = UUID.randomUUID();
        double minLon = centerLon - halfWidthDegrees;
        double maxLon = centerLon + halfWidthDegrees;
        double minLat = centerLat - halfWidthDegrees;
        double maxLat = centerLat + halfWidthDegrees;
        String wkt = String.format(
            Locale.ROOT,
            "POLYGON((%f %f, %f %f, %f %f, %f %f, %f %f))",
            minLon, minLat, maxLon, minLat, maxLon, maxLat, minLon, maxLat, minLon, minLat
        );
        try (
            PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO geofences (id, organization_id, name, area, rule, is_active, created_at) "
                    + "VALUES (?, ?, ?, ST_GeomFromText(?, 4326)::geography, ?, ?, ?)"
            )
        ) {
            statement.setObject(1, id);
            statement.setObject(2, organizationId);
            statement.setString(3, name);
            statement.setString(4, wkt);
            statement.setString(5, rule);
            statement.setBoolean(6, isActive);
            statement.setTimestamp(7, Timestamp.from(Instant.now()));
            statement.executeUpdate();
        }
        return id;
    }

    private static void insertVehicleFenceState(
        Connection connection, UUID vehicleId, UUID geofenceId, boolean isInside, Instant since
    ) throws SQLException {
        try (
            PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO vehicle_fence_state (vehicle_id, geofence_id, is_inside, since) VALUES (?, ?, ?, ?)"
            )
        ) {
            statement.setObject(1, vehicleId);
            statement.setObject(2, geofenceId);
            statement.setBoolean(3, isInside);
            statement.setTimestamp(4, Timestamp.from(since));
            statement.executeUpdate();
        }
    }
}

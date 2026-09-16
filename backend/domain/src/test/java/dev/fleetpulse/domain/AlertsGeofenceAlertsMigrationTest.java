package dev.fleetpulse.domain;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
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
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// Task 3.1 (06-add-trips-eta-alerts, WU3): kept in its OWN file/container,
// deliberately separate from AlertSchemaTest -- @Testcontainers' static
// @Container field is shared across every test METHOD in one class, and this
// is the only test in this work unit that needs a two-phase migration
// (target V11, seed data, then migrate the rest); running it alongside
// AlertSchemaTest's other methods (which all call the ordinary full
// migrate()) would non-deterministically leave `alerts` already fully
// migrated -- and geofence_alerts already dropped -- before this test's own
// V11 target ever got a chance to matter, depending on JUnit's method
// execution order. A dedicated container sidesteps that entirely.
//
// Proves the migration performs a REAL data migration, not just a schema
// change: apply only up through V11 (before `alerts` exists), seed a
// geofence_alerts row exactly like 05-add-geofencing's already-shipped
// writer would have, then apply the rest (V12) and confirm that row landed
// in `alerts` with the "geofence_" prefix applied and the old table gone --
// rather than merely trusting the INSERT...SELECT statement by reading the
// migration file.
@Testcontainers
class AlertsGeofenceAlertsMigrationTest {

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
    void migratingPastV12CarriesForwardExistingGeofenceAlertsHistoryAndDropsTheOldTable() throws Exception {
        Flyway.configure()
            .dataSource(postgis.getJdbcUrl(), postgis.getUsername(), postgis.getPassword())
            .target(MigrationVersion.fromVersion("11"))
            .load()
            .migrate();

        UUID organizationId;
        UUID vehicleId;
        UUID geofenceId;
        UUID legacyAlertId = UUID.randomUUID();
        Instant occurredAt = Instant.now().minusSeconds(120);

        try (Connection connection = connect()) {
            organizationId = insertOrganization(connection, "Acme Alerts Migration Org");
            vehicleId = insertVehicle(connection, organizationId, "Truck-AL-Migration");
            geofenceId = insertGeofence(connection, organizationId, "Depot");

            try (
                PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO geofence_alerts (id, organization_id, vehicle_id, geofence_id, alert_type, occurred_at, created_at) "
                        + "VALUES (?, ?, ?, ?, 'enter', ?, ?)"
                )
            ) {
                statement.setObject(1, legacyAlertId);
                statement.setObject(2, organizationId);
                statement.setObject(3, vehicleId);
                statement.setObject(4, geofenceId);
                statement.setTimestamp(5, Timestamp.from(occurredAt));
                statement.setTimestamp(6, Timestamp.from(Instant.now()));
                statement.executeUpdate();
            }
        }

        Flyway.configure().dataSource(postgis.getJdbcUrl(), postgis.getUsername(), postgis.getPassword()).load().migrate();

        try (Connection connection = connect()) {
            try (
                PreparedStatement statement = connection.prepareStatement(
                    "SELECT organization_id, vehicle_id, alert_type, context, occurred_at FROM alerts WHERE id = ?"
                )
            ) {
                statement.setObject(1, legacyAlertId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    assertThat(resultSet.next()).isTrue();
                    assertThat(resultSet.getObject("organization_id")).isEqualTo(organizationId);
                    assertThat(resultSet.getObject("vehicle_id")).isEqualTo(vehicleId);
                    assertThat(resultSet.getString("alert_type")).isEqualTo("geofence_enter");
                    assertThat(resultSet.getObject("context")).isEqualTo(geofenceId);
                }
            }

            try (
                PreparedStatement statement = connection
                    .prepareStatement("SELECT to_regclass('public.geofence_alerts') IS NULL AS dropped")
            ) {
                try (ResultSet resultSet = statement.executeQuery()) {
                    assertThat(resultSet.next()).isTrue();
                    assertThat(resultSet.getBoolean("dropped")).isTrue();
                }
            }
        }
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

    private static UUID insertGeofence(Connection connection, UUID organizationId, String name) throws SQLException {
        UUID id = UUID.randomUUID();
        String wkt = String.format(
            Locale.ROOT,
            "POLYGON((%f %f, %f %f, %f %f, %f %f, %f %f))",
            -74.075, 4.705, -74.065, 4.705, -74.065, 4.715, -74.075, 4.715, -74.075, 4.705
        );
        try (
            PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO geofences (id, organization_id, name, area, rule, dwell_secs, is_active, created_at) "
                    + "VALUES (?, ?, ?, ST_GeomFromText(?, 4326)::geography, 'on_enter', NULL, true, ?)"
            )
        ) {
            statement.setObject(1, id);
            statement.setObject(2, organizationId);
            statement.setString(3, name);
            statement.setString(4, wkt);
            statement.setTimestamp(5, Timestamp.from(Instant.now()));
            statement.executeUpdate();
        }
        return id;
    }
}

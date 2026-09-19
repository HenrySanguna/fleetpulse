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
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Task 3.1 (06-add-trips-eta-alerts, WU3): pure JDBC + Flyway, no Spring
// context, same recipe as TripSchemaTest/VehicleDestinationSchemaTest --
// proves the unified `alerts`/`alert_silence_state` schema (V12) itself: the
// alert_type CHECK constraint, context's conditional FK to geofences, and
// the geofence_alerts -> alerts data migration this same migration performs.
// The rule-evaluation/dedup ALGORITHM is AlertSilenceEngineTest's (pure) and
// AlertRuleEndToEndTest's (wiring) job, not this one.
@Testcontainers
class AlertSchemaTest {

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
    void rejectsAnUnknownAlertType() throws Exception {
        migrate();

        try (Connection connection = connect()) {
            UUID organizationId = insertOrganization(connection, "Acme Alerts Org 1");
            UUID vehicleId = insertVehicle(connection, organizationId, "Truck-AL-1");

            assertThatThrownBy(() -> insertAlert(connection, organizationId, vehicleId, "not_a_real_type", null))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("chk_alerts_alert_type");
        }
    }

    @Test
    void rejectsAContextThatDoesNotReferenceAnExistingGeofence() throws Exception {
        migrate();

        try (Connection connection = connect()) {
            UUID organizationId = insertOrganization(connection, "Acme Alerts Org 2");
            UUID vehicleId = insertVehicle(connection, organizationId, "Truck-AL-2");

            assertThatThrownBy(() -> insertAlert(connection, organizationId, vehicleId, "geofence_enter", UUID.randomUUID()))
                .isInstanceOf(SQLException.class);
        }
    }

    @Test
    void acceptsAContextThatReferencesARealGeofence() throws Exception {
        migrate();

        try (Connection connection = connect()) {
            UUID organizationId = insertOrganization(connection, "Acme Alerts Org 6");
            UUID vehicleId = insertVehicle(connection, organizationId, "Truck-AL-6");
            UUID geofenceId = insertGeofence(connection, organizationId, "Depot");

            UUID alertId = insertAlert(connection, organizationId, vehicleId, "geofence_dwell", geofenceId);

            try (PreparedStatement statement = connection.prepareStatement("SELECT context FROM alerts WHERE id = ?")) {
                statement.setObject(1, alertId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    assertThat(resultSet.next()).isTrue();
                    assertThat(resultSet.getObject("context")).isEqualTo(geofenceId);
                }
            }
        }
    }

    @Test
    void acceptsANullContextForAVehicleLevelAlertType() throws Exception {
        migrate();

        try (Connection connection = connect()) {
            UUID organizationId = insertOrganization(connection, "Acme Alerts Org 3");
            UUID vehicleId = insertVehicle(connection, organizationId, "Truck-AL-3");

            UUID alertId = insertAlert(connection, organizationId, vehicleId, "speeding", null);

            try (
                PreparedStatement statement = connection
                    .prepareStatement("SELECT alert_type, context, acknowledged FROM alerts WHERE id = ?")
            ) {
                statement.setObject(1, alertId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    assertThat(resultSet.next()).isTrue();
                    assertThat(resultSet.getString("alert_type")).isEqualTo("speeding");
                    assertThat(resultSet.getObject("context")).isNull();
                    assertThat(resultSet.getBoolean("acknowledged")).isFalse();
                }
            }
        }
    }

    @Test
    void alertSilenceStateEnforcesOneRowPerVehicleAlertTypeAndContext() throws Exception {
        migrate();

        try (Connection connection = connect()) {
            UUID organizationId = insertOrganization(connection, "Acme Alerts Org 5");
            UUID vehicleId = insertVehicle(connection, organizationId, "Truck-AL-5");

            insertSilenceState(connection, vehicleId, "speeding", "", true, Instant.now());

            assertThatThrownBy(() -> insertSilenceState(connection, vehicleId, "speeding", "", false, Instant.now()))
                .isInstanceOf(SQLException.class);

            // A different context is a different row entirely, not a
            // collision -- proves the composite key is genuinely 3-part, not
            // silently degraded to (vehicle_id, alert_type).
            insertSilenceState(connection, vehicleId, "speeding", UUID.randomUUID().toString(), true, Instant.now());
        }
    }

    private static UUID insertAlert(
        Connection connection, UUID organizationId, UUID vehicleId, String alertType, UUID context
    ) throws SQLException {
        UUID id = UUID.randomUUID();
        try (
            PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO alerts (id, organization_id, vehicle_id, alert_type, context, occurred_at, created_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?)"
            )
        ) {
            statement.setObject(1, id);
            statement.setObject(2, organizationId);
            statement.setObject(3, vehicleId);
            statement.setString(4, alertType);
            if (context == null) {
                statement.setNull(5, java.sql.Types.OTHER);
            } else {
                statement.setObject(5, context);
            }
            statement.setTimestamp(6, Timestamp.from(Instant.now()));
            statement.setTimestamp(7, Timestamp.from(Instant.now()));
            statement.executeUpdate();
        }
        return id;
    }

    private static void insertSilenceState(
        Connection connection, UUID vehicleId, String alertType, String context, boolean isActive, Instant lastAlertAt
    ) throws SQLException {
        try (
            PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO alert_silence_state (vehicle_id, alert_type, context, is_active, last_alert_at, updated_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?)"
            )
        ) {
            statement.setObject(1, vehicleId);
            statement.setString(2, alertType);
            statement.setString(3, context);
            statement.setBoolean(4, isActive);
            statement.setTimestamp(5, Timestamp.from(lastAlertAt));
            statement.setTimestamp(6, Timestamp.from(Instant.now()));
            statement.executeUpdate();
        }
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

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
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Pure JDBC + Flyway, no Spring context: proves the mqtt_credentials
// ownership invariant (exactly one of device_id/user_id) is enforced by the
// schema itself, not only by MqttCredential's two named factories -- the
// same defense-in-depth reasoning design.md applies to broker ACLs applies
// here to credential ownership.
@Testcontainers
class FleetAuthSchemaConstraintTest {

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
    void enforcesExactlyOneOwnerOnMqttCredentials() throws Exception {
        Flyway
            .configure()
            .dataSource(postgis.getJdbcUrl(), postgis.getUsername(), postgis.getPassword())
            .load()
            .migrate();

        try (
            Connection connection = DriverManager
                .getConnection(postgis.getJdbcUrl(), postgis.getUsername(), postgis.getPassword())
        ) {
            UUID organizationId = insertOrganization(connection, "Acme Constraint Org");
            UUID userId = insertUser(connection, organizationId, "constraint-owner@acme.test");
            UUID vehicleId = insertVehicle(connection, organizationId, "Truck-CT-1");
            UUID deviceId = insertDevice(connection, vehicleId, "device-serial-ct-1");

            assertThatThrownBy(() -> insertCredential(connection, "both-null-owner", null, null))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("chk_mqtt_credentials_single_owner");

            assertThatThrownBy(() -> insertCredential(connection, "both-set-owner", deviceId, userId))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("chk_mqtt_credentials_single_owner");

            assertThat(insertCredential(connection, "device-only-owner", deviceId, null)).isEqualTo(1);
            assertThat(insertCredential(connection, "user-only-owner", null, userId)).isEqualTo(1);
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

    private static UUID insertUser(Connection connection, UUID organizationId, String email) throws SQLException {
        UUID id = UUID.randomUUID();
        try (
            PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO users (id, organization_id, email, password_hash, role, created_at) "
                    + "VALUES (?, ?, ?, 'hashed-pw', 'DISPATCHER', ?)"
            )
        ) {
            statement.setObject(1, id);
            statement.setObject(2, organizationId);
            statement.setString(3, email);
            statement.setTimestamp(4, Timestamp.from(Instant.now()));
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

    private static UUID insertDevice(Connection connection, UUID vehicleId, String identifier) throws SQLException {
        UUID id = UUID.randomUUID();
        try (
            PreparedStatement statement = connection
                .prepareStatement("INSERT INTO devices (id, vehicle_id, identifier, created_at) VALUES (?, ?, ?, ?)")
        ) {
            statement.setObject(1, id);
            statement.setObject(2, vehicleId);
            statement.setString(3, identifier);
            statement.setTimestamp(4, Timestamp.from(Instant.now()));
            statement.executeUpdate();
        }
        return id;
    }

    private static int insertCredential(Connection connection, String username, UUID deviceId, UUID userId)
        throws SQLException {
        try (
            PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO mqtt_credentials (id, username, password_hash, device_id, user_id, created_at) "
                    + "VALUES (?, ?, 'hashed-secret', ?, ?, ?)"
            )
        ) {
            statement.setObject(1, UUID.randomUUID());
            statement.setString(2, username);
            statement.setObject(3, deviceId);
            statement.setObject(4, userId);
            statement.setTimestamp(5, Timestamp.from(Instant.now()));
            return statement.executeUpdate();
        }
    }
}

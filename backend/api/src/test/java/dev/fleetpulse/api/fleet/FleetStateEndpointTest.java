package dev.fleetpulse.api.fleet;

import dev.fleetpulse.api.security.DispatcherLoginTestSupport;
import dev.fleetpulse.domain.Organization;
import dev.fleetpulse.domain.OrganizationRepository;
import dev.fleetpulse.domain.User;
import dev.fleetpulse.domain.UserRepository;
import dev.fleetpulse.domain.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
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
import static org.assertj.core.api.Assertions.tuple;

// Task 2.1: GET /api/fleet/state -- org-scoped snapshot of the requesting
// dispatcher's fleet (design.md's "snapshot + stream" startup sequence,
// HTTP half). vehicle_state has no JPA entity (PostGIS geography column), so
// seeding goes through raw JDBC, same recipe as TelemetrySchemaTest
// (domain module) and TelemetryEndToEndIngestTest (processor module).
@Testcontainers
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class FleetStateEndpointTest {

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

    @DynamicPropertySource
    static void backingServices(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgis::getJdbcUrl);
        registry.add("spring.datasource.username", postgis::getUsername);
        registry.add("spring.datasource.password", postgis::getPassword);
        registry.add("fleetpulse.mqtt.broker-url", () -> "tcp://localhost:1");
    }

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private OrganizationRepository organizations;

    @Autowired
    private UserRepository users;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void returnsOnlyTheDispatchersOwnOrganizationVehiclesWithTheirLatestKnownState() throws SQLException {
        Organization orgA = organizations.save(new Organization("acme-fleet-state-a"));
        Organization orgB = organizations.save(new Organization("acme-fleet-state-b"));
        users.save(new User(orgA, "fleet-a@acme.test", passwordEncoder.encode("s3cret-pass"), UserRole.DISPATCHER));

        UUID reportingVehicleId = seedVehicle(orgA.getId(), "Truck-A1");
        UUID silentVehicleId = seedVehicle(orgA.getId(), "Truck-A2");
        seedVehicle(orgB.getId(), "Truck-B1");

        Instant recordedAt = Instant.parse("2026-09-14T10:00:00Z");
        seedVehicleState(reportingVehicleId, 4.704, -74.049, recordedAt, "MOVING", true);
        // silentVehicleId deliberately gets no vehicle_state row: never
        // reported telemetry, must still appear (offline, null fields).

        HttpHeaders session = DispatcherLoginTestSupport.sessionHeadersFrom(
            DispatcherLoginTestSupport.login(restTemplate, baseUrl(), "fleet-a@acme.test", "s3cret-pass"));

        ResponseEntity<FleetStateResponse> response = restTemplate.exchange(
            baseUrl() + "/api/fleet/state", HttpMethod.GET, new HttpEntity<>(session), FleetStateResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().vehicles())
            .extracting(
                VehicleStateResponse::vehicleId, VehicleStateResponse::label,
                VehicleStateResponse::lat, VehicleStateResponse::lon, VehicleStateResponse::online)
            .containsExactlyInAnyOrder(
                tuple(reportingVehicleId, "Truck-A1", 4.704, -74.049, true),
                tuple(silentVehicleId, "Truck-A2", null, null, false)
            );

        VehicleStateResponse reporting = response.getBody().vehicles().stream()
            .filter(v -> v.vehicleId().equals(reportingVehicleId))
            .findFirst().orElseThrow();
        assertThat(reporting.recordedAt()).isEqualTo(recordedAt);
        assertThat(reporting.motionState().name()).isEqualTo("MOVING");
    }

    private String baseUrl() {
        return "http://localhost:" + port;
    }

    private static UUID seedVehicle(UUID organizationId, String label) throws SQLException {
        UUID vehicleId = UUID.randomUUID();
        try (
            Connection connection = connect();
            PreparedStatement statement = connection
                .prepareStatement("INSERT INTO vehicles (id, organization_id, label, created_at) VALUES (?, ?, ?, ?)")
        ) {
            statement.setObject(1, vehicleId);
            statement.setObject(2, organizationId);
            statement.setString(3, label);
            statement.setTimestamp(4, Timestamp.from(Instant.now()));
            statement.executeUpdate();
        }
        return vehicleId;
    }

    private static void seedVehicleState(
            UUID vehicleId, double lat, double lon, Instant recordedAt, String motionState, boolean online) throws SQLException {
        try (
            Connection connection = connect();
            PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO vehicle_state (vehicle_id, location, recorded_at, motion_state, online) "
                    + "VALUES (?, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography, ?, ?, ?)")
        ) {
            statement.setObject(1, vehicleId);
            statement.setDouble(2, lon);
            statement.setDouble(3, lat);
            statement.setTimestamp(4, Timestamp.from(recordedAt));
            statement.setString(5, motionState);
            statement.setBoolean(6, online);
            statement.executeUpdate();
        }
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(postgis.getJdbcUrl(), postgis.getUsername(), postgis.getPassword());
    }
}

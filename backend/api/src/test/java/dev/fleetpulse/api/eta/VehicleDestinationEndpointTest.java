package dev.fleetpulse.api.eta;

import dev.fleetpulse.api.geofencing.GeoPointRequest;
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
import org.springframework.http.MediaType;
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

// Task 2.1: PUT/DELETE /api/vehicles/{id}/destination -- real Testcontainers
// PostGIS, same recipe as FleetStateEndpointTest/GeofenceEndpointTest. Any
// authenticated DISPATCHER (not FLEET_ADMIN) can assign/clear a destination
// -- VehicleDestinationController's own documented decision, proven here by
// deliberately using a plain DISPATCHER role, unlike GeofenceEndpointTest's
// FLEET_ADMIN fixtures.
@Testcontainers
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class VehicleDestinationEndpointTest {

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
    void assignsAndReplacesAndClearsADestinationForADispatcherOwnVehicle() throws SQLException {
        Organization org = organizations.save(new Organization("acme-eta-destination"));
        users.save(new User(org, "eta-destination@acme.test", passwordEncoder.encode("s3cret-pass"), UserRole.DISPATCHER));
        UUID vehicleId = seedVehicle(org.getId(), "Truck-ETA-1");
        HttpHeaders mutation = mutationHeaders("eta-destination@acme.test");

        ResponseEntity<VehicleDestinationResponse> assigned = restTemplate.exchange(
            baseUrl() + "/api/vehicles/" + vehicleId + "/destination",
            HttpMethod.PUT,
            new HttpEntity<>(new GeoPointRequest(4.80, -74.10), mutation),
            VehicleDestinationResponse.class
        );
        assertThat(assigned.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(assigned.getBody()).isNotNull();
        assertThat(assigned.getBody().vehicleId()).isEqualTo(vehicleId);
        assertThat(assigned.getBody().lat()).isEqualTo(4.80);
        assertThat(assigned.getBody().lon()).isEqualTo(-74.10);
        assertThat(assigned.getBody().etaSeconds()).isNull();

        // Reassigning REPLACES the previous destination -- one row per
        // vehicle, task 2.1's own upsert semantics.
        ResponseEntity<VehicleDestinationResponse> replaced = restTemplate.exchange(
            baseUrl() + "/api/vehicles/" + vehicleId + "/destination",
            HttpMethod.PUT,
            new HttpEntity<>(new GeoPointRequest(4.90, -74.20), mutation),
            VehicleDestinationResponse.class
        );
        assertThat(replaced.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replaced.getBody().lat()).isEqualTo(4.90);
        assertThat(replaced.getBody().lon()).isEqualTo(-74.20);
        assertThat(countDestinationRows(vehicleId)).isEqualTo(1);

        ResponseEntity<Void> cleared = restTemplate.exchange(
            baseUrl() + "/api/vehicles/" + vehicleId + "/destination", HttpMethod.DELETE, new HttpEntity<>(mutation), Void.class);
        assertThat(cleared.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(countDestinationRows(vehicleId)).isEqualTo(0);
    }

    @Test
    void hidesAVehicleInAnotherOrganizationAsNotFound() throws SQLException {
        Organization ownOrg = organizations.save(new Organization("acme-eta-own-org"));
        Organization otherOrg = organizations.save(new Organization("acme-eta-other-org"));
        users.save(new User(ownOrg, "eta-cross-org@acme.test", passwordEncoder.encode("s3cret-pass"), UserRole.DISPATCHER));
        UUID otherOrgVehicleId = seedVehicle(otherOrg.getId(), "Truck-ETA-Other");
        HttpHeaders mutation = mutationHeaders("eta-cross-org@acme.test");

        ResponseEntity<String> response = restTemplate.exchange(
            baseUrl() + "/api/vehicles/" + otherOrgVehicleId + "/destination",
            HttpMethod.PUT,
            new HttpEntity<>(new GeoPointRequest(4.80, -74.10), mutation),
            String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void clearingAnAlreadyUnassignedVehicleIsAHarmlessNoOp() throws SQLException {
        Organization org = organizations.save(new Organization("acme-eta-clear-noop"));
        users.save(new User(org, "eta-clear-noop@acme.test", passwordEncoder.encode("s3cret-pass"), UserRole.DISPATCHER));
        UUID vehicleId = seedVehicle(org.getId(), "Truck-ETA-Clear");
        HttpHeaders mutation = mutationHeaders("eta-clear-noop@acme.test");

        ResponseEntity<Void> cleared = restTemplate.exchange(
            baseUrl() + "/api/vehicles/" + vehicleId + "/destination", HttpMethod.DELETE, new HttpEntity<>(mutation), Void.class);

        assertThat(cleared.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    private HttpHeaders mutationHeaders(String email) {
        HttpHeaders headers = DispatcherLoginTestSupport.mutationHeadersFrom(
            DispatcherLoginTestSupport.login(restTemplate, baseUrl(), email, "s3cret-pass"));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private String baseUrl() {
        return "http://localhost:" + port;
    }

    private static int countDestinationRows(UUID vehicleId) throws SQLException {
        try (
            Connection connection = connect();
            PreparedStatement statement = connection
                .prepareStatement("SELECT count(*) FROM vehicle_destinations WHERE vehicle_id = ?")
        ) {
            statement.setObject(1, vehicleId);
            var resultSet = statement.executeQuery();
            resultSet.next();
            return resultSet.getInt(1);
        }
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

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(postgis.getJdbcUrl(), postgis.getUsername(), postgis.getPassword());
    }
}

package dev.fleetpulse.api.alerts;

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
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// Task 3.4: GET /api/alerts (with filters) and PATCH /api/alerts/{id}/acknowledge
// -- real Testcontainers PostGIS, same recipe as GeofenceEndpointTest/
// VehicleDestinationEndpointTest. `alerts` (V12, WU3) has no API-level write
// path of its own (only the processor module inserts alert rows), so every
// fixture here seeds directly via raw SQL, the same shape
// GeofenceEndpointTest.seedGeofence() already established for a table this
// module only reads.
@Testcontainers
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class AlertsEndpointTest {

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
    void listsOrgAlertsNewestFirstWithVehicleAndGeofenceLabelsJoined() throws SQLException {
        Organization org = organizations.save(new Organization("acme-alerts-list"));
        users.save(new User(org, "alerts-list@acme.test", passwordEncoder.encode("s3cret-pass"), UserRole.DISPATCHER));
        UUID vehicleId = seedVehicle(org.getId(), "Truck-Alerts-1");
        UUID geofenceId = seedGeofence(org.getId(), "Puerto de Valencia");
        Instant now = Instant.now();
        seedAlert(UUID.randomUUID(), org.getId(), vehicleId, "speeding", null, now.minus(2, ChronoUnit.MINUTES));
        seedAlert(UUID.randomUUID(), org.getId(), vehicleId, "geofence_enter", geofenceId, now);

        ResponseEntity<AlertResponse[]> response = restTemplate.exchange(
            baseUrl() + "/api/alerts", HttpMethod.GET, new HttpEntity<>(sessionHeaders("alerts-list@acme.test")), AlertResponse[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(2);
        assertThat(response.getBody()[0].alertType()).isEqualTo("geofence_enter");
        assertThat(response.getBody()[0].contextLabel()).isEqualTo("Puerto de Valencia");
        assertThat(response.getBody()[0].vehicleLabel()).isEqualTo("Truck-Alerts-1");
        assertThat(response.getBody()[1].alertType()).isEqualTo("speeding");
        assertThat(response.getBody()[1].contextLabel()).isNull();
    }

    @Test
    void filtersByTypeAcknowledgedAndDateRange() throws SQLException {
        Organization org = organizations.save(new Organization("acme-alerts-filters"));
        users.save(new User(org, "alerts-filters@acme.test", passwordEncoder.encode("s3cret-pass"), UserRole.DISPATCHER));
        UUID vehicleId = seedVehicle(org.getId(), "Truck-Alerts-Filter");
        Instant now = Instant.now();
        UUID speedingId = UUID.randomUUID();
        seedAlert(speedingId, org.getId(), vehicleId, "speeding", null, now.minus(1, ChronoUnit.HOURS));
        seedAlert(UUID.randomUUID(), org.getId(), vehicleId, "excessive_idle", null, now.minus(10, ChronoUnit.DAYS));
        acknowledgeDirectly(speedingId);

        ResponseEntity<AlertResponse[]> byType = restTemplate.exchange(
            baseUrl() + "/api/alerts?type=speeding", HttpMethod.GET,
            new HttpEntity<>(sessionHeaders("alerts-filters@acme.test")), AlertResponse[].class);
        assertThat(byType.getBody()).extracting(AlertResponse::alertType).containsExactly("speeding");

        ResponseEntity<AlertResponse[]> byAcknowledged = restTemplate.exchange(
            baseUrl() + "/api/alerts?acknowledged=false", HttpMethod.GET,
            new HttpEntity<>(sessionHeaders("alerts-filters@acme.test")), AlertResponse[].class);
        assertThat(byAcknowledged.getBody()).extracting(AlertResponse::alertType).containsExactly("excessive_idle");

        ResponseEntity<AlertResponse[]> byRange = restTemplate.exchange(
            baseUrl() + "/api/alerts?from=" + now.minus(2, ChronoUnit.HOURS) + "&to=" + now, HttpMethod.GET,
            new HttpEntity<>(sessionHeaders("alerts-filters@acme.test")), AlertResponse[].class);
        assertThat(byRange.getBody()).extracting(AlertResponse::alertType).containsExactly("speeding");
    }

    @Test
    void rejectsAnUnknownAlertTypeFilterAsBadRequest() throws SQLException {
        Organization org = organizations.save(new Organization("acme-alerts-bad-type"));
        users.save(new User(org, "alerts-bad-type@acme.test", passwordEncoder.encode("s3cret-pass"), UserRole.DISPATCHER));

        ResponseEntity<String> response = restTemplate.exchange(
            baseUrl() + "/api/alerts?type=not-a-real-type", HttpMethod.GET,
            new HttpEntity<>(sessionHeaders("alerts-bad-type@acme.test")), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void marksAnAlertAsAcknowledgedIdempotently() throws SQLException {
        Organization org = organizations.save(new Organization("acme-alerts-ack"));
        users.save(new User(org, "alerts-ack@acme.test", passwordEncoder.encode("s3cret-pass"), UserRole.DISPATCHER));
        UUID vehicleId = seedVehicle(org.getId(), "Truck-Alerts-Ack");
        UUID alertId = UUID.randomUUID();
        seedAlert(alertId, org.getId(), vehicleId, "speeding", null, Instant.now());
        HttpHeaders mutation = DispatcherLoginTestSupport.mutationHeadersFrom(
            restTemplate, baseUrl(), DispatcherLoginTestSupport.login(restTemplate, baseUrl(), "alerts-ack@acme.test", "s3cret-pass"));

        ResponseEntity<AlertResponse> first = restTemplate.exchange(
            baseUrl() + "/api/alerts/" + alertId + "/acknowledge", HttpMethod.PATCH, new HttpEntity<>(mutation), AlertResponse.class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(first.getBody().acknowledged()).isTrue();

        ResponseEntity<AlertResponse> second = restTemplate.exchange(
            baseUrl() + "/api/alerts/" + alertId + "/acknowledge", HttpMethod.PATCH, new HttpEntity<>(mutation), AlertResponse.class);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getBody().acknowledged()).isTrue();
    }

    @Test
    void hidesAnotherOrganizationsAlertsFromListAndAcknowledge() throws SQLException {
        Organization orgA = organizations.save(new Organization("acme-alerts-iso-a"));
        Organization orgB = organizations.save(new Organization("acme-alerts-iso-b"));
        users.save(new User(orgA, "alerts-iso-a@acme.test", passwordEncoder.encode("s3cret-pass"), UserRole.DISPATCHER));
        UUID vehicleInOrgB = seedVehicle(orgB.getId(), "Truck-Alerts-Other-Org");
        UUID alertInOrgB = UUID.randomUUID();
        seedAlert(alertInOrgB, orgB.getId(), vehicleInOrgB, "speeding", null, Instant.now());

        ResponseEntity<AlertResponse[]> listed = restTemplate.exchange(
            baseUrl() + "/api/alerts", HttpMethod.GET, new HttpEntity<>(sessionHeaders("alerts-iso-a@acme.test")), AlertResponse[].class);
        assertThat(listed.getBody()).isEmpty();

        HttpHeaders mutation = DispatcherLoginTestSupport.mutationHeadersFrom(
            restTemplate, baseUrl(), DispatcherLoginTestSupport.login(restTemplate, baseUrl(), "alerts-iso-a@acme.test", "s3cret-pass"));
        ResponseEntity<String> acknowledged = restTemplate.exchange(
            baseUrl() + "/api/alerts/" + alertInOrgB + "/acknowledge", HttpMethod.PATCH, new HttpEntity<>(mutation), String.class);
        assertThat(acknowledged.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private HttpHeaders sessionHeaders(String email) {
        return DispatcherLoginTestSupport.sessionHeadersFrom(
            DispatcherLoginTestSupport.login(restTemplate, baseUrl(), email, "s3cret-pass"));
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

    private static UUID seedGeofence(UUID organizationId, String name) throws SQLException {
        UUID geofenceId = UUID.randomUUID();
        try (
            Connection connection = connect();
            PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO geofences (id, organization_id, name, area, rule, is_active, created_at) "
                    + "VALUES (?, ?, ?, ST_GeomFromText(?, 4326)::geography, 'on_enter', true, ?)")
        ) {
            statement.setObject(1, geofenceId);
            statement.setObject(2, organizationId);
            statement.setString(3, name);
            statement.setString(4, "POLYGON((-74.05 4.70, -74.04 4.70, -74.04 4.71, -74.05 4.71, -74.05 4.70))");
            statement.setTimestamp(5, Timestamp.from(Instant.now()));
            statement.executeUpdate();
        }
        return geofenceId;
    }

    private static void seedAlert(UUID id, UUID organizationId, UUID vehicleId, String alertType, UUID context, Instant occurredAt)
            throws SQLException {
        try (
            Connection connection = connect();
            PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO alerts (id, organization_id, vehicle_id, alert_type, context, occurred_at, created_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?)")
        ) {
            statement.setObject(1, id);
            statement.setObject(2, organizationId);
            statement.setObject(3, vehicleId);
            statement.setString(4, alertType);
            statement.setObject(5, context);
            statement.setTimestamp(6, Timestamp.from(occurredAt));
            statement.setTimestamp(7, Timestamp.from(Instant.now()));
            statement.executeUpdate();
        }
    }

    private static void acknowledgeDirectly(UUID id) throws SQLException {
        try (
            Connection connection = connect();
            PreparedStatement statement = connection.prepareStatement("UPDATE alerts SET acknowledged = true WHERE id = ?")
        ) {
            statement.setObject(1, id);
            statement.executeUpdate();
        }
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(postgis.getJdbcUrl(), postgis.getUsername(), postgis.getPassword());
    }
}

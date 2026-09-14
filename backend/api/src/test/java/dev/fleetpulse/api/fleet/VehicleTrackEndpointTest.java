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

// Task 2.1 extension: GET /api/vehicles/{id}/track -- simplified historical
// track (design.md: "la traza histórica llega ya simplificada desde el
// backend, Geo.simplifyTrack en geo-core"). positions has no JPA entity
// (PostGIS geography column), so seeding goes through raw JDBC -- same
// recipe as TelemetryEndToEndIngestTest (processor module); its partition
// exists immediately after Flyway migrate because V6 calls
// partman.create_parent() with p_premake=4 at migration time, so plain
// Instant.now()-based timestamps land without any extra partition setup.
@Testcontainers
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class VehicleTrackEndpointTest {

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

    // A 0.00005-degree latitude wobble (~5.6m) is well within the
    // application's default 15m tolerance (FleetpulseTrackProperties):
    // Geo.simplifyTrack's own 3-point branch compares it directly against
    // the first-last chord and collapses it away.
    @Test
    void collapsesANegligibleDeviationWithinTheConfiguredTolerance() throws SQLException {
        Organization org = organizations.save(new Organization("acme-track-collapse"));
        users.save(new User(org, "track-a@acme.test", passwordEncoder.encode("s3cret-pass"), UserRole.DISPATCHER));
        UUID vehicleId = seedVehicle(org.getId(), "Truck-Track-1");

        Instant t0 = Instant.parse("2026-09-14T08:00:00Z");
        seedPosition(vehicleId, t0, 0.0, 0.0);
        seedPosition(vehicleId, t0.plusSeconds(5), 0.00005, 0.01);
        seedPosition(vehicleId, t0.plusSeconds(10), 0.0, 0.02);

        HttpHeaders session = DispatcherLoginTestSupport.sessionHeadersFrom(
            DispatcherLoginTestSupport.login(restTemplate, baseUrl(), "track-a@acme.test", "s3cret-pass"));

        ResponseEntity<TrackPointResponse[]> response = restTemplate.exchange(
            baseUrl() + "/api/vehicles/" + vehicleId + "/track",
            HttpMethod.GET, new HttpEntity<>(session), TrackPointResponse[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsExactly(
            new TrackPointResponse(0.0, 0.0, t0),
            new TrackPointResponse(0.0, 0.02, t0.plusSeconds(10)));
    }

    // Reuses geo-core's own GeoSimplifyTrackTest fixture (a 0.001-degree
    // latitude detour, ~111m of cross-track deviation) -- geo-core's own
    // suite already proves it is kept even at a more generous 50m tolerance,
    // so it must also be kept at this application's stricter 15m default.
    @Test
    void preservesADeviationThatExceedsTheConfiguredTolerance() throws SQLException {
        Organization org = organizations.save(new Organization("acme-track-preserve"));
        users.save(new User(org, "track-e@acme.test", passwordEncoder.encode("s3cret-pass"), UserRole.DISPATCHER));
        UUID vehicleId = seedVehicle(org.getId(), "Truck-Track-5");

        Instant t0 = Instant.parse("2026-09-14T08:00:00Z");
        seedPosition(vehicleId, t0, 0.0, 0.0);
        seedPosition(vehicleId, t0.plusSeconds(5), 0.001, 0.01);
        seedPosition(vehicleId, t0.plusSeconds(10), 0.0, 0.02);

        HttpHeaders session = DispatcherLoginTestSupport.sessionHeadersFrom(
            DispatcherLoginTestSupport.login(restTemplate, baseUrl(), "track-e@acme.test", "s3cret-pass"));

        ResponseEntity<TrackPointResponse[]> response = restTemplate.exchange(
            baseUrl() + "/api/vehicles/" + vehicleId + "/track",
            HttpMethod.GET, new HttpEntity<>(session), TrackPointResponse[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsExactly(
            new TrackPointResponse(0.0, 0.0, t0),
            new TrackPointResponse(0.001, 0.01, t0.plusSeconds(5)),
            new TrackPointResponse(0.0, 0.02, t0.plusSeconds(10)));
    }

    @Test
    void filtersTheTrackByTheFromAndToQueryParameters() throws SQLException {
        Organization org = organizations.save(new Organization("acme-track-range"));
        users.save(new User(org, "track-b@acme.test", passwordEncoder.encode("s3cret-pass"), UserRole.DISPATCHER));
        UUID vehicleId = seedVehicle(org.getId(), "Truck-Track-2");

        Instant early = Instant.parse("2026-09-14T06:00:00Z");
        Instant late = Instant.parse("2026-09-14T09:00:00Z");
        seedPosition(vehicleId, early, 1.0, 1.0);
        seedPosition(vehicleId, late, 2.0, 2.0);

        HttpHeaders session = DispatcherLoginTestSupport.sessionHeadersFrom(
            DispatcherLoginTestSupport.login(restTemplate, baseUrl(), "track-b@acme.test", "s3cret-pass"));

        String url = baseUrl() + "/api/vehicles/" + vehicleId + "/track?from=" + early.plusSeconds(1) + "&to=" + late.plusSeconds(1);
        ResponseEntity<TrackPointResponse[]> response = restTemplate.exchange(
            url, HttpMethod.GET, new HttpEntity<>(session), TrackPointResponse[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsExactly(new TrackPointResponse(2.0, 2.0, late));
    }

    @Test
    void rejectsAMalformedInstantWithBadRequest() throws SQLException {
        Organization org = organizations.save(new Organization("acme-track-bad-instant"));
        users.save(new User(org, "track-c@acme.test", passwordEncoder.encode("s3cret-pass"), UserRole.DISPATCHER));
        UUID vehicleId = seedVehicle(org.getId(), "Truck-Track-3");

        HttpHeaders session = DispatcherLoginTestSupport.sessionHeadersFrom(
            DispatcherLoginTestSupport.login(restTemplate, baseUrl(), "track-c@acme.test", "s3cret-pass"));

        ResponseEntity<String> response = restTemplate.exchange(
            baseUrl() + "/api/vehicles/" + vehicleId + "/track?from=not-an-instant",
            HttpMethod.GET, new HttpEntity<>(session), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void hidesAVehicleFromAnotherOrganizationAsNotFound() throws SQLException {
        Organization orgA = organizations.save(new Organization("acme-track-isolation-a"));
        Organization orgB = organizations.save(new Organization("acme-track-isolation-b"));
        users.save(new User(orgA, "track-d@acme.test", passwordEncoder.encode("s3cret-pass"), UserRole.DISPATCHER));
        UUID vehicleInOrgB = seedVehicle(orgB.getId(), "Truck-Track-4");

        HttpHeaders session = DispatcherLoginTestSupport.sessionHeadersFrom(
            DispatcherLoginTestSupport.login(restTemplate, baseUrl(), "track-d@acme.test", "s3cret-pass"));

        ResponseEntity<String> response = restTemplate.exchange(
            baseUrl() + "/api/vehicles/" + vehicleInOrgB + "/track",
            HttpMethod.GET, new HttpEntity<>(session), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
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

    private static void seedPosition(UUID vehicleId, Instant recordedAt, double lat, double lon) throws SQLException {
        try (
            Connection connection = connect();
            PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO positions (vehicle_id, recorded_at, location) "
                    + "VALUES (?, ?, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography)")
        ) {
            statement.setObject(1, vehicleId);
            statement.setTimestamp(2, Timestamp.from(recordedAt));
            statement.setDouble(3, lon);
            statement.setDouble(4, lat);
            statement.executeUpdate();
        }
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(postgis.getJdbcUrl(), postgis.getUsername(), postgis.getPassword());
    }
}

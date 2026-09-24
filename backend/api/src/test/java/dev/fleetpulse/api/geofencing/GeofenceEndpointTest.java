package dev.fleetpulse.api.geofencing;

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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// Task 5.2 (backend half, WU6): CRUD for /api/geofences with real
// Testcontainers PostGIS -- no mocks for DB-backed logic, per the launch
// prompt. Covers create/read/update/delete, org-scoping (a geofence in
// another organization is hidden as 404), and geometry validation rejection
// (a self-intersecting polygon and a degenerate all-identical-vertices
// polygon both exercise VALIDATE_POLYGON_SQL for real against the live
// container, not a mocked predicate).
@Testcontainers
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class GeofenceEndpointTest {

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
    void createsListsGetsUpdatesAndDeletesAPolygonGeofence() {
        Organization org = organizations.save(new Organization("acme-geofence-crud"));
        users.save(new User(org, "geofence-crud@acme.test", passwordEncoder.encode("s3cret-pass"), UserRole.FLEET_ADMIN));
        HttpHeaders mutation = mutationHeaders("geofence-crud@acme.test");

        GeofenceRequest createRequest = new GeofenceRequest(
            "Depot", GeofenceRuleType.ON_ENTER, null, GeofenceShapeType.POLYGON, depotRectangle(), null, null);
        ResponseEntity<GeofenceResponse> created = restTemplate.exchange(
            baseUrl() + "/api/geofences", HttpMethod.POST, new HttpEntity<>(createRequest, mutation), GeofenceResponse.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody()).isNotNull();
        UUID id = created.getBody().id();
        assertThat(created.getBody().name()).isEqualTo("Depot");
        assertThat(created.getBody().rule()).isEqualTo(GeofenceRuleType.ON_ENTER);
        assertThat(created.getBody().dwellSecs()).isNull();
        // 4 distinct vertices + the repeated closing vertex the server adds.
        assertThat(created.getBody().vertices()).hasSize(5);

        ResponseEntity<GeofenceResponse[]> listed = restTemplate.exchange(
            baseUrl() + "/api/geofences", HttpMethod.GET, new HttpEntity<>(sessionHeaders("geofence-crud@acme.test")), GeofenceResponse[].class);
        assertThat(listed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listed.getBody()).extracting(GeofenceResponse::id).containsExactly(id);

        ResponseEntity<GeofenceResponse> fetched = restTemplate.exchange(
            baseUrl() + "/api/geofences/" + id, HttpMethod.GET, new HttpEntity<>(sessionHeaders("geofence-crud@acme.test")), GeofenceResponse.class);
        assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(fetched.getBody().name()).isEqualTo("Depot");

        GeofenceRequest updateRequest = new GeofenceRequest(
            "Depot (renamed)", GeofenceRuleType.ON_DWELL, 120, GeofenceShapeType.POLYGON, depotRectangle(), null, null);
        ResponseEntity<GeofenceResponse> updated = restTemplate.exchange(
            baseUrl() + "/api/geofences/" + id, HttpMethod.PUT, new HttpEntity<>(updateRequest, mutation), GeofenceResponse.class);
        assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(updated.getBody().name()).isEqualTo("Depot (renamed)");
        assertThat(updated.getBody().rule()).isEqualTo(GeofenceRuleType.ON_DWELL);
        assertThat(updated.getBody().dwellSecs()).isEqualTo(120);

        ResponseEntity<Void> deleted = restTemplate.exchange(
            baseUrl() + "/api/geofences/" + id, HttpMethod.DELETE, new HttpEntity<>(mutation), Void.class);
        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<String> afterDelete = restTemplate.exchange(
            baseUrl() + "/api/geofences/" + id, HttpMethod.GET, new HttpEntity<>(sessionHeaders("geofence-crud@acme.test")), String.class);
        assertThat(afterDelete.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        ResponseEntity<GeofenceResponse[]> listedAfterDelete = restTemplate.exchange(
            baseUrl() + "/api/geofences", HttpMethod.GET, new HttpEntity<>(sessionHeaders("geofence-crud@acme.test")), GeofenceResponse[].class);
        assertThat(listedAfterDelete.getBody()).isEmpty();
    }

    @Test
    void createsACircleGeofenceBufferedFromCenterAndRadiusServerSide() {
        Organization org = organizations.save(new Organization("acme-geofence-circle"));
        users.save(new User(org, "geofence-circle@acme.test", passwordEncoder.encode("s3cret-pass"), UserRole.FLEET_ADMIN));
        HttpHeaders mutation = mutationHeaders("geofence-circle@acme.test");

        GeofenceRequest createRequest = new GeofenceRequest(
            "Depot Circle", GeofenceRuleType.ON_EXIT, null, GeofenceShapeType.CIRCLE,
            null, new GeoPointRequest(4.70, -74.05), 50.0);
        ResponseEntity<GeofenceResponse> created = restTemplate.exchange(
            baseUrl() + "/api/geofences", HttpMethod.POST, new HttpEntity<>(createRequest, mutation), GeofenceResponse.class);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        // A center+radius buffer produces a many-vertex polygon approximation
        // (ST_Buffer's default segmentation), never a bare 4/5-point ring --
        // proves the server actually computed ST_Buffer rather than echoing
        // the input back as a degenerate "polygon".
        assertThat(created.getBody().vertices().size()).isGreaterThan(8);
    }

    @Test
    void rejectsASelfIntersectingPolygonAsInvalidGeometry() {
        Organization org = organizations.save(new Organization("acme-geofence-bowtie"));
        users.save(new User(org, "geofence-bowtie@acme.test", passwordEncoder.encode("s3cret-pass"), UserRole.FLEET_ADMIN));
        HttpHeaders mutation = mutationHeaders("geofence-bowtie@acme.test");

        // Classic bowtie/hourglass self-intersection: connecting the 4
        // corners of a square in A-B-C-D order where A-B and C-D are the
        // square's two diagonals, crossing at its center.
        List<GeoPointRequest> bowtie = List.of(
            new GeoPointRequest(4.70, -74.05),
            new GeoPointRequest(4.71, -74.04),
            new GeoPointRequest(4.71, -74.05),
            new GeoPointRequest(4.70, -74.04)
        );
        GeofenceRequest request = new GeofenceRequest(
            "Bowtie", GeofenceRuleType.ON_ENTER, null, GeofenceShapeType.POLYGON, bowtie, null, null);
        ResponseEntity<String> response = restTemplate.exchange(
            baseUrl() + "/api/geofences", HttpMethod.POST, new HttpEntity<>(request, mutation), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void rejectsADegeneratePolygonWithAllIdenticalVerticesAsInvalidGeometry() {
        Organization org = organizations.save(new Organization("acme-geofence-degenerate"));
        users.save(new User(org, "geofence-degenerate@acme.test", passwordEncoder.encode("s3cret-pass"), UserRole.FLEET_ADMIN));
        HttpHeaders mutation = mutationHeaders("geofence-degenerate@acme.test");

        List<GeoPointRequest> allSamePoint = List.of(
            new GeoPointRequest(4.70, -74.05),
            new GeoPointRequest(4.70, -74.05),
            new GeoPointRequest(4.70, -74.05)
        );
        GeofenceRequest request = new GeofenceRequest(
            "Degenerate", GeofenceRuleType.ON_ENTER, null, GeofenceShapeType.POLYGON, allSamePoint, null, null);
        ResponseEntity<String> response = restTemplate.exchange(
            baseUrl() + "/api/geofences", HttpMethod.POST, new HttpEntity<>(request, mutation), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void rejectsAnOnDwellRuleWithoutDwellSecsAsBadRequest() {
        Organization org = organizations.save(new Organization("acme-geofence-dwell-missing"));
        users.save(new User(org, "geofence-dwell@acme.test", passwordEncoder.encode("s3cret-pass"), UserRole.FLEET_ADMIN));
        HttpHeaders mutation = mutationHeaders("geofence-dwell@acme.test");

        GeofenceRequest request = new GeofenceRequest(
            "No Dwell Secs", GeofenceRuleType.ON_DWELL, null, GeofenceShapeType.POLYGON, depotRectangle(), null, null);
        ResponseEntity<String> response = restTemplate.exchange(
            baseUrl() + "/api/geofences", HttpMethod.POST, new HttpEntity<>(request, mutation), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void hidesAnotherOrganizationsGeofenceAsNotFoundOnGet() throws SQLException {
        Organization orgA = organizations.save(new Organization("acme-geofence-isolation-a"));
        Organization orgB = organizations.save(new Organization("acme-geofence-isolation-b"));
        users.save(new User(orgA, "geofence-iso-a@acme.test", passwordEncoder.encode("s3cret-pass"), UserRole.DISPATCHER));
        UUID geofenceInOrgB = seedGeofence(orgB.getId(), "Org B Depot");

        ResponseEntity<String> response = restTemplate.exchange(
            baseUrl() + "/api/geofences/" + geofenceInOrgB,
            HttpMethod.GET, new HttpEntity<>(sessionHeaders("geofence-iso-a@acme.test")), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void excludesAnotherOrganizationsGeofenceFromTheList() throws SQLException {
        Organization orgA = organizations.save(new Organization("acme-geofence-list-a"));
        Organization orgB = organizations.save(new Organization("acme-geofence-list-b"));
        users.save(new User(orgA, "geofence-list-a@acme.test", passwordEncoder.encode("s3cret-pass"), UserRole.DISPATCHER));
        seedGeofence(orgB.getId(), "Org B Depot");

        ResponseEntity<GeofenceResponse[]> response = restTemplate.exchange(
            baseUrl() + "/api/geofences",
            HttpMethod.GET, new HttpEntity<>(sessionHeaders("geofence-list-a@acme.test")), GeofenceResponse[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEmpty();
    }

    @Test
    void preventsUpdatingAnotherOrganizationsGeofenceReturningNotFound() throws SQLException {
        Organization orgA = organizations.save(new Organization("acme-geofence-update-isolation-a"));
        Organization orgB = organizations.save(new Organization("acme-geofence-update-isolation-b"));
        users.save(new User(orgA, "geofence-upd-a@acme.test", passwordEncoder.encode("s3cret-pass"), UserRole.FLEET_ADMIN));
        UUID geofenceInOrgB = seedGeofence(orgB.getId(), "Org B Depot");

        GeofenceRequest updateRequest = new GeofenceRequest(
            "Hijacked", GeofenceRuleType.ON_ENTER, null, GeofenceShapeType.POLYGON, depotRectangle(), null, null);
        ResponseEntity<String> response = restTemplate.exchange(
            baseUrl() + "/api/geofences/" + geofenceInOrgB,
            HttpMethod.PUT, new HttpEntity<>(updateRequest, mutationHeaders("geofence-upd-a@acme.test")), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void preventsDeletingAnotherOrganizationsGeofenceReturningNotFound() throws SQLException {
        Organization orgA = organizations.save(new Organization("acme-geofence-delete-isolation-a"));
        Organization orgB = organizations.save(new Organization("acme-geofence-delete-isolation-b"));
        users.save(new User(orgA, "geofence-del-a@acme.test", passwordEncoder.encode("s3cret-pass"), UserRole.FLEET_ADMIN));
        users.save(new User(orgB, "geofence-del-b@acme.test", passwordEncoder.encode("s3cret-pass"), UserRole.DISPATCHER));
        UUID geofenceInOrgB = seedGeofence(orgB.getId(), "Org B Depot");

        ResponseEntity<String> response = restTemplate.exchange(
            baseUrl() + "/api/geofences/" + geofenceInOrgB,
            HttpMethod.DELETE, new HttpEntity<>(mutationHeaders("geofence-del-a@acme.test")), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // Confirms the org-B row itself was left completely untouched (still
        // active, still visible through org B's own session) -- not
        // silently soft-deleted despite the cross-org 404 above.
        ResponseEntity<GeofenceResponse> stillActive = restTemplate.exchange(
            baseUrl() + "/api/geofences/" + geofenceInOrgB,
            HttpMethod.GET, new HttpEntity<>(sessionHeaders("geofence-del-b@acme.test")), GeofenceResponse.class);
        assertThat(stillActive.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(stillActive.getBody().name()).isEqualTo("Org B Depot");
    }

    private static List<GeoPointRequest> depotRectangle() {
        return List.of(
            new GeoPointRequest(4.70, -74.05),
            new GeoPointRequest(4.70, -74.04),
            new GeoPointRequest(4.71, -74.04),
            new GeoPointRequest(4.71, -74.05)
        );
    }

    private HttpHeaders sessionHeaders(String email) {
        return DispatcherLoginTestSupport.sessionHeadersFrom(
            DispatcherLoginTestSupport.login(restTemplate, baseUrl(), email, "s3cret-pass"));
    }

    private HttpHeaders mutationHeaders(String email) {
        HttpHeaders headers = DispatcherLoginTestSupport.mutationHeadersFrom(
            restTemplate, baseUrl(), DispatcherLoginTestSupport.login(restTemplate, baseUrl(), email, "s3cret-pass"));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private String baseUrl() {
        return "http://localhost:" + port;
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

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(postgis.getJdbcUrl(), postgis.getUsername(), postgis.getPassword());
    }
}

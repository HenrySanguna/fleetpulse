package dev.fleetpulse.api.security;

import dev.fleetpulse.api.geofencing.GeoPointRequest;
import dev.fleetpulse.api.geofencing.GeofenceRequest;
import dev.fleetpulse.api.geofencing.GeofenceResponse;
import dev.fleetpulse.api.geofencing.GeofenceRuleType;
import dev.fleetpulse.api.geofencing.GeofenceShapeType;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// cross-site-csrf-token, task 1: GET /api/csrf (CsrfTokenController) plus
// SecurityConfig's switch to HttpSessionCsrfTokenRepository. POST
// /api/geofences (GeofenceController, already covered end-to-end by
// GeofenceEndpointTest) stands in for "an existing mutating endpoint" here --
// cheaper to reach than alert acknowledge, since it needs no raw-SQL fixture
// beyond the org/user every test here already seeds for login.
@Testcontainers
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class CsrfTokenEndpointTest {

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
    void returnsATokenForAnAuthenticatedSession() {
        seedDispatcher("acme-csrf-token", "csrf-token@acme.test", UserRole.DISPATCHER);
        HttpHeaders session = sessionHeaders("csrf-token@acme.test");

        ResponseEntity<CsrfTokenResponse> response = restTemplate.exchange(
            baseUrl() + "/api/csrf", HttpMethod.GET, new HttpEntity<>(session), CsrfTokenResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().headerName()).isEqualTo("X-CSRF-TOKEN");
        assertThat(response.getBody().token()).isNotBlank();
    }

    @Test
    void rejectsAnUnauthenticatedRequestForTheToken() {
        ResponseEntity<String> response = restTemplate.getForEntity(baseUrl() + "/api/csrf", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void rejectsAnUnsafeRequestWithoutAToken() {
        seedDispatcher("acme-csrf-missing", "csrf-missing@acme.test", UserRole.FLEET_ADMIN);
        HttpHeaders session = sessionHeaders("csrf-missing@acme.test");
        session.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<String> response = restTemplate.exchange(
            baseUrl() + "/api/geofences", HttpMethod.POST, new HttpEntity<>(depotRequest(), session), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void acceptsAnUnsafeRequestWithTheEndpointsToken() {
        seedDispatcher("acme-csrf-valid", "csrf-valid@acme.test", UserRole.FLEET_ADMIN);
        HttpHeaders mutation = mutationHeaders("csrf-valid@acme.test");

        ResponseEntity<GeofenceResponse> response = restTemplate.exchange(
            baseUrl() + "/api/geofences", HttpMethod.POST, new HttpEntity<>(depotRequest(), mutation), GeofenceResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void rejectsATokenFromADifferentSession() {
        seedDispatcher("acme-csrf-other-a", "csrf-other-a@acme.test", UserRole.FLEET_ADMIN);
        seedDispatcher("acme-csrf-other-b", "csrf-other-b@acme.test", UserRole.FLEET_ADMIN);

        CsrfTokenResponse tokenFromSessionA = fetchCsrfToken("csrf-other-a@acme.test");
        HttpHeaders sessionB = sessionHeaders("csrf-other-b@acme.test");
        sessionB.setContentType(MediaType.APPLICATION_JSON);
        sessionB.add(tokenFromSessionA.headerName(), tokenFromSessionA.token());

        ResponseEntity<String> response = restTemplate.exchange(
            baseUrl() + "/api/geofences", HttpMethod.POST, new HttpEntity<>(depotRequest(), sessionB), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    private void seedDispatcher(String orgName, String email, UserRole role) {
        Organization org = organizations.save(new Organization(orgName));
        users.save(new User(org, email, passwordEncoder.encode("s3cret-pass"), role));
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

    private CsrfTokenResponse fetchCsrfToken(String email) {
        ResponseEntity<CsrfTokenResponse> response = restTemplate.exchange(
            baseUrl() + "/api/csrf", HttpMethod.GET, new HttpEntity<>(sessionHeaders(email)), CsrfTokenResponse.class);
        CsrfTokenResponse token = response.getBody();
        assertThat(token).isNotNull();
        return token;
    }

    private static GeofenceRequest depotRequest() {
        List<GeoPointRequest> depotRectangle = List.of(
            new GeoPointRequest(4.70, -74.05),
            new GeoPointRequest(4.70, -74.04),
            new GeoPointRequest(4.71, -74.04),
            new GeoPointRequest(4.71, -74.05)
        );
        return new GeofenceRequest("Depot", GeofenceRuleType.ON_ENTER, null, GeofenceShapeType.POLYGON, depotRectangle, null, null);
    }

    private String baseUrl() {
        return "http://localhost:" + port;
    }
}

package dev.fleetpulse.api.mqtt.credentials;

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

import static org.assertj.core.api.Assertions.assertThat;

// sdd-verify remediation for 02-add-fleet-auth: spec scenario "Renovación
// tras revocar la sesión del despachador" (requirement "Expiración de
// credenciales efímeras de navegador") had no covering test -- tasks.md
// section 6 never allocated a task for it, so the gap predated WU4. Mirrors
// DispatcherDeactivationSessionInvalidationTest's pattern (same filter, same
// deactivate-then-retry shape) but targets the actual endpoint the scenario
// is about: GET /api/mqtt/credentials. No Mosquitto broker is needed --
// DeactivatedDispatcherSessionFilter/anyRequest().authenticated() reject the
// request before the controller (and therefore BrowserMqttCredentialService)
// is ever reached, so this stays a pure HTTP-layer proof, like the sibling
// test it mirrors. Active-session credential issuance against a real broker
// is already covered by CrossOrganizationMqttIsolationTest and
// ExpiredBrowserCredentialConnectionTest.
@Testcontainers
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class MqttCredentialsAfterDispatcherDeactivationTest {

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
    void rejectsMqttCredentialRenewalOnTheNextRequestAfterDeactivation() {
        Organization org = organizations.save(new Organization("acme-mqtt-creds-deactivate"));
        User dispatcher = users
            .save(new User(org, "priya@acme.test", passwordEncoder.encode("s3cret-pass"), UserRole.DISPATCHER));

        HttpHeaders session = DispatcherLoginTestSupport.sessionHeadersFrom(
            DispatcherLoginTestSupport.login(restTemplate, baseUrl(), "priya@acme.test", "s3cret-pass"));

        dispatcher.deactivate();
        users.save(dispatcher);

        ResponseEntity<String> afterDeactivation = restTemplate
            .exchange(baseUrl() + "/api/mqtt/credentials", HttpMethod.GET, new HttpEntity<>(session), String.class);
        assertThat(afterDeactivation.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // Triangulation: the session must be genuinely destroyed, not merely
        // skipped once -- a retry with the exact same stale cookie must keep
        // failing.
        ResponseEntity<String> stillRejected = restTemplate
            .exchange(baseUrl() + "/api/mqtt/credentials", HttpMethod.GET, new HttpEntity<>(session), String.class);
        assertThat(stillRejected.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private String baseUrl() {
        return "http://localhost:" + port;
    }
}

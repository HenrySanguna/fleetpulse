package dev.fleetpulse.api.security;

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

// Task 2.5 / spec scenario 6.7: "Desactivar un despachador invalida su sesión
// HTTP en la petición siguiente" -- design.md's whole reason for using a
// server-side session instead of a self-contained token.
@Testcontainers
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class DispatcherDeactivationSessionInvalidationTest {

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
    void invalidatesTheDispatcherSessionOnTheNextRequestAfterDeactivation() {
        Organization org = organizations.save(new Organization("acme-deactivate"));
        User dispatcher = users
            .save(new User(org, "gio@acme.test", passwordEncoder.encode("s3cret-pass"), UserRole.DISPATCHER));

        HttpHeaders session = DispatcherLoginTestSupport.sessionHeadersFrom(
            DispatcherLoginTestSupport.login(restTemplate, baseUrl(), "gio@acme.test", "s3cret-pass"));

        ResponseEntity<String> beforeDeactivation = restTemplate
            .exchange(baseUrl() + "/api/dispatchers/me", HttpMethod.GET, new HttpEntity<>(session), String.class);
        assertThat(beforeDeactivation.getStatusCode()).isEqualTo(HttpStatus.OK);

        dispatcher.deactivate();
        users.save(dispatcher);

        ResponseEntity<String> afterDeactivation = restTemplate
            .exchange(baseUrl() + "/api/dispatchers/me", HttpMethod.GET, new HttpEntity<>(session), String.class);
        assertThat(afterDeactivation.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // Triangulation: the session must be genuinely destroyed, not merely
        // skipped once -- a retry with the exact same stale cookie must keep
        // failing.
        ResponseEntity<String> stillRejected = restTemplate
            .exchange(baseUrl() + "/api/dispatchers/me", HttpMethod.GET, new HttpEntity<>(session), String.class);
        assertThat(stillRejected.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void leavesAnActiveDispatcherSessionUsableAcrossMultipleRequests() {
        Organization org = organizations.save(new Organization("acme-stay-active"));
        users.save(new User(org, "remy@acme.test", passwordEncoder.encode("s3cret-pass"), UserRole.DISPATCHER));

        HttpHeaders session = DispatcherLoginTestSupport.sessionHeadersFrom(
            DispatcherLoginTestSupport.login(restTemplate, baseUrl(), "remy@acme.test", "s3cret-pass"));

        ResponseEntity<String> first = restTemplate
            .exchange(baseUrl() + "/api/dispatchers/me", HttpMethod.GET, new HttpEntity<>(session), String.class);
        ResponseEntity<String> second = restTemplate
            .exchange(baseUrl() + "/api/dispatchers/me", HttpMethod.GET, new HttpEntity<>(session), String.class);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private String baseUrl() {
        return "http://localhost:" + port;
    }
}

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

// Task 2.4: role-based authorization via method annotations. GET
// /api/dispatchers/{id} is @PreAuthorize("hasRole('FLEET_ADMIN')") on
// DispatcherSessionController -- a plain DISPATCHER must be denied even
// though they are authenticated.
@Testcontainers
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class DispatcherRoleAuthorizationTest {

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
    void deniesTheFleetAdminOnlyEndpointToAPlainDispatcher() {
        Organization org = organizations.save(new Organization("acme-role-deny"));
        User target = users.save(new User(org, "target1@acme.test", passwordEncoder.encode("s3cret-pass"), UserRole.DISPATCHER));
        users.save(new User(org, "disp1@acme.test", passwordEncoder.encode("s3cret-pass"), UserRole.DISPATCHER));

        HttpHeaders session = DispatcherLoginTestSupport.sessionHeadersFrom(
            DispatcherLoginTestSupport.login(restTemplate, baseUrl(), "disp1@acme.test", "s3cret-pass"));

        ResponseEntity<String> response = restTemplate.exchange(
            baseUrl() + "/api/dispatchers/" + target.getId(),
            HttpMethod.GET,
            new HttpEntity<>(session),
            String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void allowsTheFleetAdminOnlyEndpointToAFleetAdmin() {
        Organization org = organizations.save(new Organization("acme-role-allow"));
        User target = users.save(new User(org, "target2@acme.test", passwordEncoder.encode("s3cret-pass"), UserRole.DISPATCHER));
        users.save(new User(org, "admin2@acme.test", passwordEncoder.encode("s3cret-pass"), UserRole.FLEET_ADMIN));

        HttpHeaders session = DispatcherLoginTestSupport.sessionHeadersFrom(
            DispatcherLoginTestSupport.login(restTemplate, baseUrl(), "admin2@acme.test", "s3cret-pass"));

        ResponseEntity<DispatcherSelfView> response = restTemplate.exchange(
            baseUrl() + "/api/dispatchers/" + target.getId(),
            HttpMethod.GET,
            new HttpEntity<>(session),
            DispatcherSelfView.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().email()).isEqualTo("target2@acme.test");
    }

    @Test
    void hidesADispatcherFromAnotherOrganizationAsNotFound() {
        Organization orgA = organizations.save(new Organization("acme-role-orga"));
        Organization orgB = organizations.save(new Organization("acme-role-orgb"));
        User targetInOrgB = users.save(new User(orgB, "target3@acme.test", passwordEncoder.encode("s3cret-pass"), UserRole.DISPATCHER));
        users.save(new User(orgA, "admin3@acme.test", passwordEncoder.encode("s3cret-pass"), UserRole.FLEET_ADMIN));

        HttpHeaders session = DispatcherLoginTestSupport.sessionHeadersFrom(
            DispatcherLoginTestSupport.login(restTemplate, baseUrl(), "admin3@acme.test", "s3cret-pass"));

        ResponseEntity<String> response = restTemplate.exchange(
            baseUrl() + "/api/dispatchers/" + targetInOrgB.getId(),
            HttpMethod.GET,
            new HttpEntity<>(session),
            String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private String baseUrl() {
        return "http://localhost:" + port;
    }
}

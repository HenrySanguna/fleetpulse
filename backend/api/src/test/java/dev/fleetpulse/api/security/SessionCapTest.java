package dev.fleetpulse.api.security;

import dev.fleetpulse.domain.Organization;
import dev.fleetpulse.domain.OrganizationRepository;
import dev.fleetpulse.domain.User;
import dev.fleetpulse.domain.UserRepository;
import dev.fleetpulse.domain.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class SessionCapTest {

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
        registry.add("fleetpulse.security.max-sessions-per-user", () -> "2");
    }

    @LocalServerPort
    private int port;

    @Autowired
    private OrganizationRepository organizations;

    @Autowired
    private UserRepository users;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private FindByIndexNameSessionRepository<?> sessions;

    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    void evictsTheOldestSessionsBeyondThePerUserCap() throws Exception {
        Organization org = organizations.save(new Organization("acme-session-cap"));
        users.save(new User(org, "cap@acme.test", passwordEncoder.encode("s3cret-pass"), UserRole.DISPATCHER));

        String first = loginAndGetSessionCookie("cap@acme.test");
        String second = loginAndGetSessionCookie("cap@acme.test");
        String third = loginAndGetSessionCookie("cap@acme.test");

        assertThat(sessions.findByPrincipalName("cap@acme.test")).hasSize(2);
        assertThat(sessions.findByPrincipalName("cap@acme.test").values())
            .allSatisfy(session -> assertThat(session.getMaxInactiveInterval()).isEqualTo(java.time.Duration.ofMinutes(20)));
        assertThat(statusOfMe(first)).isEqualTo(401);
        assertThat(statusOfMe(second)).isEqualTo(200);
        assertThat(statusOfMe(third)).isEqualTo(200);
    }

    @Test
    void doesNotEvictSessionsOfOtherUsers() throws Exception {
        Organization org = organizations.save(new Organization("acme-session-cap-other"));
        users.save(new User(org, "one@acme.test", passwordEncoder.encode("s3cret-pass"), UserRole.DISPATCHER));
        users.save(new User(org, "two@acme.test", passwordEncoder.encode("s3cret-pass"), UserRole.DISPATCHER));

        String otherUser = loginAndGetSessionCookie("two@acme.test");
        loginAndGetSessionCookie("one@acme.test");
        loginAndGetSessionCookie("one@acme.test");
        loginAndGetSessionCookie("one@acme.test");

        assertThat(statusOfMe(otherUser)).isEqualTo(200);
    }

    private String loginAndGetSessionCookie(String email) throws Exception {
        HttpResponse<String> response = client.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/login"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("username=" + email.replace("@", "%40") + "&password=s3cret-pass"))
                .build(),
            HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return response.headers().allValues("Set-Cookie").stream()
            .filter(cookie -> cookie.startsWith("SESSION="))
            .map(cookie -> cookie.split(";", 2)[0])
            .findFirst()
            .orElseThrow();
    }

    private int statusOfMe(String sessionCookie) throws Exception {
        return client.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/dispatchers/me"))
                .header("Cookie", sessionCookie)
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString()).statusCode();
    }
}

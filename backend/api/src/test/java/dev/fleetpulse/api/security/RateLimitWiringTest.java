package dev.fleetpulse.api.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class RateLimitWiringTest {

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
        registry.add("fleetpulse.security.rate-limit.login-per-minute", () -> "2");
    }

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void throttlesRepeatedLoginAttemptsFromTheSameAddress() throws Exception {
        String baseUrl = "http://localhost:" + port;

        for (int i = 0; i < 2; i++) {
            ResponseEntity<String> attempt = DispatcherLoginTestSupport.login(restTemplate, baseUrl, "nobody@acme.test", "wrong");
            assertThat(attempt.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        HttpResponse<String> throttled = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/login"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("username=nobody%40acme.test&password=wrong"))
                .build(),
            HttpResponse.BodyHandlers.ofString());

        assertThat(throttled.statusCode()).isEqualTo(429);
        assertThat(throttled.headers().firstValue("Retry-After")).isPresent();
    }
}

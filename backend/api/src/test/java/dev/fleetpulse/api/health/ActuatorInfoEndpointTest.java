package dev.fleetpulse.api.health;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

// spring.autoconfigure.exclude keeps the context free of a real DataSource /
// Hibernate / Flyway boot: build-info/git-properties wiring must not depend
// on database connectivity being available (same pattern as
// OpenApiDocumentPublicationTest).
@AutoConfigureTestRestTemplate
@SpringBootTest(
    webEnvironment = RANDOM_PORT,
    properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
            + "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration,"
            + "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration",
        "spring.datasource.url=jdbc:postgresql://localhost:5432/fleetpulse",
        "spring.datasource.username=fleetpulse",
        "spring.datasource.password=fleetpulse",
        "fleetpulse.mqtt.broker-url=tcp://localhost:1883"
    }
)
class ActuatorInfoEndpointTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Test
    void infoEndpointExposesTheRealDeployedCommitSha() throws Exception {
        String expectedSha = currentGitCommitSha();

        ResponseEntity<String> response = restTemplate
            .getForEntity("http://localhost:" + port + "/actuator/info", String.class);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();

        JsonNode body = jsonMapper.readTree(response.getBody());

        assertThat(body.path("build").path("version").asString()).isNotBlank();
        // management.info.git.mode=full nests id.abbrev/id.describe/id.full instead
        // of the simple-mode flat string; id.full is the complete 40-char SHA.
        String commitSha = body.path("git").path("commit").path("id").path("full").asString();
        assertThat(commitSha).matches("^[0-9a-f]{40}$");
        assertThat(commitSha).isEqualTo(expectedSha);
    }

    private static String currentGitCommitSha() throws Exception {
        Path repoRoot = Path
            .of(System.getProperty("user.dir"), "..", "..")
            .normalize();
        Process process = new ProcessBuilder("git", "rev-parse", "HEAD")
            .directory(repoRoot.toFile())
            .start();
        String output;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            output = reader.readLine();
        }
        process.waitFor();
        return output.trim();
    }
}

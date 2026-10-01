package dev.fleetpulse.api.health;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

// A stand-in `db` indicator replaces the real DataSource one (excluded, like
// ActuatorInfoEndpointTest) so the group wiring is verified without a database.
@AutoConfigureTestRestTemplate
@Import(ContainerHealthGroupTest.StubDbIndicator.class)
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
class ContainerHealthGroupTest {

    @TestConfiguration
    static class StubDbIndicator {
        @Bean
        HealthIndicator dbHealthIndicator() {
            return () -> Health.up().build();
        }
    }

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Test
    void containerGroupIncludesEveryIndicatorExceptDb() throws Exception {
        JsonNode components = components("/actuator/health/container");

        assertThat(components.has("db")).isFalse();
        assertThat(components.has("commit")).isTrue();
        assertThat(components.has("mqttBroker")).isTrue();
        assertThat(components.has("processorHeartbeat")).isTrue();
    }

    @Test
    void fullHealthEndpointStillIncludesDb() throws Exception {
        JsonNode components = components("/actuator/health");

        assertThat(components.has("db")).isTrue();
        assertThat(components.has("processorHeartbeat")).isTrue();
    }

    private JsonNode components(String path) throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity("http://localhost:" + port + path, String.class);
        assertThat(response.getBody()).as("status %s", response.getStatusCode()).isNotNull();
        return jsonMapper.readTree(response.getBody()).path("components");
    }
}

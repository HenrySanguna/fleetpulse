package dev.fleetpulse.api.openapi;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

// spring.autoconfigure.exclude keeps the context free of a real DataSource /
// Hibernate / Flyway boot: publishing the OpenAPI document must not depend on
// database connectivity being available.
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
class OpenApiDocumentPublicationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Test
    void openApiDocumentIsPublishedAndReachable() {
        ResponseEntity<String> response = restTemplate
            .getForEntity("http://localhost:" + port + "/v3/api-docs", String.class);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);

        JsonNode document = jsonMapper.readTree(response.getBody());

        assertThat(document.path("openapi").asString()).isNotBlank();
        assertThat(document.has("paths")).isTrue();
    }
}

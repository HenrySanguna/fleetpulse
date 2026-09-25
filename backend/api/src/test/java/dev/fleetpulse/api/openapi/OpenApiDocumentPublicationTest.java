package dev.fleetpulse.api.openapi;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

// spring.autoconfigure.exclude keeps the context free of a real DataSource /
// Hibernate / Flyway boot: publishing the OpenAPI document must not depend on
// database connectivity being available.
//
// spring.profiles.active=prod (F8): the only place server.forward-headers-
// strategy=framework is set (application-prod.yml -- prod is the only
// profile ever fronted by a reverse proxy, see that file's own comment).
// Activating it here doesn't change anything else this class exercises,
// but lets honorsForwardedProtocolWhenBuildingTheOpenApiServerUrl() prove
// the setting actually changes request.getScheme() as seen by the app.
@AutoConfigureTestRestTemplate
@SpringBootTest(
    webEnvironment = RANDOM_PORT,
    properties = {
        "spring.profiles.active=prod",
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

    // 02-add-fleet-auth, WU3 follow-up: adding Spring Security in WU2 made
    // every previously-open endpoint deny-by-default, including
    // springdoc's interactive UI over this same document -- SecurityConfig
    // now permits it for the same reason as /v3/api-docs/** above.
    @Test
    void swaggerUiIsReachableWithoutAuthentication() {
        ResponseEntity<String> response = restTemplate
            .getForEntity("http://localhost:" + port + "/swagger-ui/index.html", String.class);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
    }

    // F8: proves server.forward-headers-strategy=framework actually takes
    // effect, not just that it's set -- springdoc builds this document's
    // own "servers" entry from the request's scheme/host/port, making it a
    // convenient existing absolute-URL-bearing endpoint. There's no
    // redirect-issuing endpoint left to assert this against directly:
    // SecurityConfig's login/logout handlers were changed by this same fix
    // to never redirect at all.
    @Test
    void honorsForwardedProtocolWhenBuildingTheOpenApiServerUrl() {
        HttpHeaders forwardedHttps = new HttpHeaders();
        forwardedHttps.add("X-Forwarded-Proto", "https");

        ResponseEntity<String> response = restTemplate.exchange(
            "http://localhost:" + port + "/v3/api-docs", HttpMethod.GET,
            new HttpEntity<>(forwardedHttps), String.class);

        JsonNode document = jsonMapper.readTree(response.getBody());
        String serverUrl = document.path("servers").path(0).path("url").asString();
        assertThat(serverUrl).startsWith("https://");
    }
}

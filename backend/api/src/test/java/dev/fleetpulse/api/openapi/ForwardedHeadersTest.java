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
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

// spring.autoconfigure.exclude keeps the context free of a real DataSource /
// Hibernate / Flyway boot: proving the forwarded-header setting takes effect
// must not depend on database connectivity being available.
//
// spring.profiles.active=prod (F8): the only place server.forward-headers-
// strategy=native is set (application-prod.yml -- prod is the only profile
// ever fronted by a reverse proxy, see that file's own comment). Kept in its
// own class, separate from OpenApiDocumentPublicationTest, so activating
// prod does not silently apply to that class's other (default-profile)
// assertions too.
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
class ForwardedHeadersTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    // F8: proves server.forward-headers-strategy=native actually takes
    // effect in the prod profile, not just that it's set -- springdoc
    // builds this document's own "servers" entry from the request's
    // scheme/host/port, making it a convenient existing absolute-URL-
    // bearing endpoint. There's no redirect-issuing endpoint left to assert
    // this against directly: SecurityConfig's logout handler was changed
    // by the same fix that introduced this setting to never redirect at
    // all (login's own handler already avoided redirects before that).
    //
    // TestRestTemplate connects from 127.0.0.1, which Tomcat's RemoteIpValve
    // (native's implementation for this container) trusts as an internal
    // proxy by default, so this stays green under native the same way it
    // was under framework.
    @Test
    void honorsForwardedProtocolWhenBuildingTheOpenApiServerUrl() {
        HttpHeaders forwardedHttps = new HttpHeaders();
        forwardedHttps.add("X-Forwarded-Proto", "https");

        ResponseEntity<String> response = restTemplate.exchange(
            "http://localhost:" + port + "/v3/api-docs", HttpMethod.GET,
            new HttpEntity<>(forwardedHttps), String.class);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        JsonNode document = jsonMapper.readTree(response.getBody());
        String serverUrl = document.path("servers").path(0).path("url").asString();
        assertThat(serverUrl).startsWith("https://");
    }
}

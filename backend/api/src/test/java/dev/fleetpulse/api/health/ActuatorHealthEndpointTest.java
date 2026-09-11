package dev.fleetpulse.api.health;

import dev.fleetpulse.api.mqtt.SecuredMosquittoTestSupport;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// Full-stack proof of the spec scenario "Todos los componentes saludables":
// a real PostGIS/pg_partman database and a real Mosquitto broker are both
// reachable, and /actuator/health reports every configured component as UP.
@Testcontainers
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class ActuatorHealthEndpointTest {

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

    // 02-add-fleet-auth (tasks 5.1/5.2): the real mosquitto.conf now denies
    // anonymous connections, so this test's broker must be bootstrapped the
    // same way docker-compose.yml's mosquitto service is.
    @Container
    static final GenericContainer<?> mosquitto = SecuredMosquittoTestSupport.newContainer();

    @org.springframework.test.context.DynamicPropertySource
    static void backingServices(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgis::getJdbcUrl);
        registry.add("spring.datasource.username", postgis::getUsername);
        registry.add("spring.datasource.password", postgis::getPassword);
        registry.add(
            "fleetpulse.mqtt.broker-url",
            () -> "tcp://" + mosquitto.getHost() + ":" + mosquitto.getMappedPort(1883)
        );
        registry.add("fleetpulse.mqtt.service.username", () -> SecuredMosquittoTestSupport.SERVICE_USERNAME);
        registry.add("fleetpulse.mqtt.service.password", () -> SecuredMosquittoTestSupport.SERVICE_PASSWORD);
    }

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Test
    void reportsDatabaseBrokerAndHeartbeatComponentsAsUp() throws Exception {
        // The default heartbeat topic (FleetpulseHeartbeatProperties) has no
        // real processor publishing to this test's broker: fake its retained
        // message the same way processor would, to prove api's indicator
        // wiring end to end rather than the publisher itself (already
        // covered by ProcessorHeartbeatPublisherTest).
        publishFreshRetainedHeartbeat();

        ResponseEntity<String> response = restTemplate
            .getForEntity("http://localhost:" + port + "/actuator/health", String.class);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();

        JsonNode body = jsonMapper.readTree(response.getBody());

        assertThat(body.path("status").asString()).isEqualTo("UP");
        assertThat(body.path("components").path("db").path("status").asString()).isEqualTo("UP");
        assertThat(body.path("components").path("mqttBroker").path("status").asString()).isEqualTo("UP");
        assertThat(body.path("components").path("processorHeartbeat").path("status").asString()).isEqualTo("UP");
    }

    private void publishFreshRetainedHeartbeat() throws Exception {
        String brokerUrl = "tcp://" + mosquitto.getHost() + ":" + mosquitto.getMappedPort(1883);
        MqttClient client = new MqttClient(brokerUrl, "fleetpulse-test-publisher-" + UUID.randomUUID(), new MemoryPersistence());
        MqttConnectOptions options = new MqttConnectOptions();
        options.setConnectionTimeout(3);
        options.setAutomaticReconnect(false);
        options.setUserName(SecuredMosquittoTestSupport.SERVICE_USERNAME);
        options.setPassword(SecuredMosquittoTestSupport.SERVICE_PASSWORD.toCharArray());
        try {
            client.connect(options);
            MqttMessage message = new MqttMessage(Instant.now().toString().getBytes(StandardCharsets.UTF_8));
            message.setQos(1);
            message.setRetained(true);
            client.publish("fleetpulse/processor/heartbeat", message);
            client.disconnect();
        } finally {
            client.close();
        }
    }
}

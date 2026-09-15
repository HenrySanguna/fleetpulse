package dev.fleetpulse.processor;

import dev.fleetpulse.processor.mqtt.SecuredMosquittoTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Path;

// Every other test in this module hand-assembles a narrow
// AnnotationConfigApplicationContext with only the @Configuration classes it
// needs (see GeofenceAlertEndToEndTest, TelemetryEndToEndIngestTest, ...) --
// none of them ever boots the REAL ProcessorApplication (@SpringBootApplication
// component scan), so none of them can catch a wiring mistake the real app
// would hit: an ambiguous bean, a missing @Qualifier, a config class the
// production scan picks up that a hand-picked test context never included.
// Caught exactly that way while writing this test: production's real
// component scan sees FOUR MqttPahoClientFactory beans at once
// (mqttPahoClientFactory, alertOutboundMqttClientFactory,
// presenceInboundMqttClientFactory, telemetryInboundMqttClientFactory) --
// MqttBrokerHealthIndicator's constructor had no @Qualifier, so the real app
// failed to start entirely (confirmed live in the docker-and-compose-smoke
// CI job once issue #36's unrelated flake stopped blocking it from ever
// running). No other test setup here would ever have surfaced it.
@SpringBootTest
@Testcontainers
class ProcessorApplicationContextTest {

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

    @Container
    static final GenericContainer<?> mosquitto = SecuredMosquittoTestSupport.newContainer();

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
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

    @Test
    void contextLoads() {
        // Intentionally no assertions beyond a successful startup -- see the
        // class-level comment for what this proves that no other test can.
    }
}

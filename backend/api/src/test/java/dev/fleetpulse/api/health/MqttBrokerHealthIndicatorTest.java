package dev.fleetpulse.api.health;

import dev.fleetpulse.api.config.FleetpulseMqttProperties;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;
import org.springframework.integration.mqtt.core.DefaultMqttPahoClientFactory;
import org.springframework.integration.mqtt.core.MqttPahoClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class MqttBrokerHealthIndicatorTest {

    // Same mosquitto.conf docker-compose.yml mounts for the "mosquitto" service:
    // kept as the single source of truth instead of duplicating it here.
    private static final Path MOSQUITTO_CONF = Path
        .of(System.getProperty("user.dir"), "..", "..", "docker", "mosquitto", "mosquitto.conf")
        .normalize();

    @Container
    static final GenericContainer<?> mosquitto = new GenericContainer<>(DockerImageName.parse("eclipse-mosquitto:2"))
        .withCopyFileToContainer(MountableFile.forHostPath(MOSQUITTO_CONF), "/mosquitto/config/mosquitto.conf")
        .withExposedPorts(1883)
        .waitingFor(Wait.forListeningPort());

    @Test
    void reportsUpWhenBrokerIsReachable() {
        String brokerUrl = "tcp://" + mosquitto.getHost() + ":" + mosquitto.getMappedPort(1883);
        MqttBrokerHealthIndicator indicator = new MqttBrokerHealthIndicator(
            testClientFactory(),
            new FleetpulseMqttProperties(brokerUrl)
        );

        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void reportsDownWhenBrokerIsUnreachable() {
        int unreachablePort = mosquitto.getMappedPort(1883) + 1;
        String brokerUrl = "tcp://" + mosquitto.getHost() + ":" + unreachablePort;
        MqttBrokerHealthIndicator indicator = new MqttBrokerHealthIndicator(
            testClientFactory(),
            new FleetpulseMqttProperties(brokerUrl)
        );

        assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
    }

    private static MqttPahoClientFactory testClientFactory() {
        DefaultMqttPahoClientFactory factory = new DefaultMqttPahoClientFactory();
        MqttConnectOptions options = new MqttConnectOptions();
        options.setConnectionTimeout(3);
        options.setAutomaticReconnect(false);
        factory.setConnectionOptions(options);
        return factory;
    }
}

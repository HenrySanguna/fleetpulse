package dev.fleetpulse.api.health;

import dev.fleetpulse.api.config.FleetpulseHeartbeatProperties;
import dev.fleetpulse.api.config.FleetpulseMqttProperties;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
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

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class ProcessorHeartbeatHealthIndicatorTest {

    private static final Path MOSQUITTO_CONF = Path
        .of(System.getProperty("user.dir"), "..", "..", "docker", "mosquitto", "mosquitto.conf")
        .normalize();

    @Container
    static final GenericContainer<?> mosquitto = new GenericContainer<>(DockerImageName.parse("eclipse-mosquitto:2"))
        .withCopyFileToContainer(MountableFile.forHostPath(MOSQUITTO_CONF), "/mosquitto/config/mosquitto.conf")
        .withExposedPorts(1883)
        .waitingFor(Wait.forListeningPort());

    // Unique per test instance (JUnit 5 default PER_METHOD lifecycle): the
    // shared static Mosquitto container keeps retained messages across test
    // methods, so a fixed topic would leak a previous test's retained
    // heartbeat into "no heartbeat published yet" and similar assertions.
    private final String heartbeatTopic = "fleetpulse/processor/heartbeat-indicator-test-" + UUID.randomUUID();

    @Test
    void reportsUpWhenTheRetainedHeartbeatIsFresh() throws Exception {
        publishRetainedHeartbeat(Instant.now());
        ProcessorHeartbeatHealthIndicator indicator = newIndicator(Duration.ofSeconds(90));

        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void reportsDownWhenTheRetainedHeartbeatIsStale() throws Exception {
        publishRetainedHeartbeat(Instant.now().minus(Duration.ofMinutes(10)));
        ProcessorHeartbeatHealthIndicator indicator = newIndicator(Duration.ofSeconds(60));

        assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
    }

    @Test
    void reportsDownWhenNoHeartbeatHasEverBeenPublished() {
        ProcessorHeartbeatHealthIndicator indicator = newIndicator(Duration.ofSeconds(90));

        assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
    }

    private ProcessorHeartbeatHealthIndicator newIndicator(Duration stalenessThreshold) {
        return new ProcessorHeartbeatHealthIndicator(
            testClientFactory(),
            new FleetpulseMqttProperties(brokerUrl()),
            new FleetpulseHeartbeatProperties(heartbeatTopic, stalenessThreshold)
        );
    }

    private void publishRetainedHeartbeat(Instant timestamp) throws Exception {
        MqttClient client = new MqttClient(brokerUrl(), "fleetpulse-test-publisher-" + UUID.randomUUID(), new MemoryPersistence());
        MqttConnectOptions options = new MqttConnectOptions();
        options.setConnectionTimeout(3);
        options.setAutomaticReconnect(false);
        try {
            client.connect(options);
            MqttMessage message = new MqttMessage(timestamp.toString().getBytes(StandardCharsets.UTF_8));
            message.setQos(1);
            message.setRetained(true);
            client.publish(heartbeatTopic, message);
            client.disconnect();
        } finally {
            client.close();
        }
    }

    private static String brokerUrl() {
        return "tcp://" + mosquitto.getHost() + ":" + mosquitto.getMappedPort(1883);
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

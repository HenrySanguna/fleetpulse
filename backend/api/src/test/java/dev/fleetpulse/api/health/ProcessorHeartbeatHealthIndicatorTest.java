package dev.fleetpulse.api.health;

import dev.fleetpulse.api.config.FleetpulseHeartbeatProperties;
import dev.fleetpulse.api.config.FleetpulseMqttProperties;
import dev.fleetpulse.api.mqtt.SecuredMosquittoTestSupport;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;
import org.springframework.integration.mqtt.core.DefaultMqttPahoClientFactory;
import org.springframework.integration.mqtt.core.MqttPahoClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class ProcessorHeartbeatHealthIndicatorTest {

    // 02-add-fleet-auth (tasks 5.1/5.2): the real mosquitto.conf now denies
    // anonymous connections, so this test's broker must be bootstrapped the
    // same way docker-compose.yml's mosquitto service is.
    @Container
    static final GenericContainer<?> mosquitto = SecuredMosquittoTestSupport.newContainer();

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
        options.setUserName(SecuredMosquittoTestSupport.SERVICE_USERNAME);
        options.setPassword(SecuredMosquittoTestSupport.SERVICE_PASSWORD.toCharArray());
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
        options.setUserName(SecuredMosquittoTestSupport.SERVICE_USERNAME);
        options.setPassword(SecuredMosquittoTestSupport.SERVICE_PASSWORD.toCharArray());
        factory.setConnectionOptions(options);
        return factory;
    }
}

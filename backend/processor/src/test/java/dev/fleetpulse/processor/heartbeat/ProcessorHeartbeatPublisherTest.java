package dev.fleetpulse.processor.heartbeat;

import dev.fleetpulse.processor.config.FleetpulseHeartbeatProperties;
import dev.fleetpulse.processor.config.FleetpulseMqttProperties;
import dev.fleetpulse.processor.mqtt.SecuredMosquittoTestSupport;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.junit.jupiter.api.Test;
import org.springframework.integration.mqtt.core.DefaultMqttPahoClientFactory;
import org.springframework.integration.mqtt.core.MqttPahoClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class ProcessorHeartbeatPublisherTest {

    // 02-add-fleet-auth (tasks 5.1/5.2): the real mosquitto.conf now denies
    // anonymous connections, so this test's broker must be bootstrapped the
    // same way docker-compose.yml's mosquitto service is.
    @Container
    static final GenericContainer<?> mosquitto = SecuredMosquittoTestSupport.newContainer();

    private static final String HEARTBEAT_TOPIC = "fleetpulse/processor/heartbeat-test";

    @Test
    void publishesARetainedHeartbeatWithAFreshTimestamp() throws Exception {
        String brokerUrl = brokerUrl();
        ProcessorHeartbeatPublisher publisher = newPublisher(brokerUrl);

        publisher.publishHeartbeat();

        MqttMessage retained = awaitRetainedMessage(brokerUrl);
        assertThat(retained.isRetained()).isTrue();
        Instant published = Instant.parse(new String(retained.getPayload(), StandardCharsets.UTF_8));
        assertThat(published).isCloseTo(Instant.now(), org.assertj.core.api.Assertions.within(10, java.time.temporal.ChronoUnit.SECONDS));
    }

    @Test
    void republishingReplacesTheRetainedHeartbeatWithANewerTimestamp() throws Exception {
        String brokerUrl = brokerUrl();
        ProcessorHeartbeatPublisher publisher = newPublisher(brokerUrl);

        publisher.publishHeartbeat();
        Instant firstTimestamp = Instant.parse(
            new String(awaitRetainedMessage(brokerUrl).getPayload(), StandardCharsets.UTF_8)
        );

        Thread.sleep(50);
        publisher.publishHeartbeat();
        Instant secondTimestamp = Instant.parse(
            new String(awaitRetainedMessage(brokerUrl).getPayload(), StandardCharsets.UTF_8)
        );

        assertThat(secondTimestamp).isAfter(firstTimestamp);
    }

    private static ProcessorHeartbeatPublisher newPublisher(String brokerUrl) {
        return new ProcessorHeartbeatPublisher(
            testClientFactory(),
            new FleetpulseMqttProperties(brokerUrl),
            new FleetpulseHeartbeatProperties(HEARTBEAT_TOPIC)
        );
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

    private static MqttMessage awaitRetainedMessage(String brokerUrl) throws Exception {
        MqttClient client = new MqttClient(brokerUrl, "fleetpulse-test-subscriber-" + UUID.randomUUID(), new MemoryPersistence());
        CompletableFuture<MqttMessage> received = new CompletableFuture<>();
        client.setCallback(new org.eclipse.paho.client.mqttv3.MqttCallback() {
            @Override
            public void connectionLost(Throwable cause) {
            }

            @Override
            public void messageArrived(String topic, MqttMessage message) {
                received.complete(message);
            }

            @Override
            public void deliveryComplete(org.eclipse.paho.client.mqttv3.IMqttDeliveryToken token) {
            }
        });
        MqttConnectOptions options = new MqttConnectOptions();
        options.setConnectionTimeout(3);
        options.setAutomaticReconnect(false);
        options.setUserName(SecuredMosquittoTestSupport.SERVICE_USERNAME);
        options.setPassword(SecuredMosquittoTestSupport.SERVICE_PASSWORD.toCharArray());
        try {
            client.connect(options);
            client.subscribe(HEARTBEAT_TOPIC, 1);
            return received.get(5, TimeUnit.SECONDS);
        } finally {
            client.disconnect();
            client.close();
        }
    }
}

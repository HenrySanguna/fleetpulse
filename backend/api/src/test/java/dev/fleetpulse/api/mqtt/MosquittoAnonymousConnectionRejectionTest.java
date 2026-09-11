package dev.fleetpulse.api.mqtt;

import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Task 5.2 / spec scenario 6.6: the broker's real production posture
// (docker/mosquitto/mosquitto.conf, the same file docker-compose.yml
// mounts) must reject a credential-less connection attempt on BOTH the TCP
// listener (1883) and the WebSocket listener (9001).
@Testcontainers
class MosquittoAnonymousConnectionRejectionTest {

    @Container
    static final GenericContainer<?> mosquitto = SecuredMosquittoTestSupport.newContainer();

    @Test
    void rejectsAnAnonymousConnectionOnTheTcpListener() {
        MqttConnectOptions options = new MqttConnectOptions();
        options.setConnectionTimeout(3);
        options.setAutomaticReconnect(false);
        String brokerUrl = "tcp://" + mosquitto.getHost() + ":" + mosquitto.getMappedPort(1883);

        assertThatThrownBy(() -> connectAnonymously(brokerUrl, options)).isInstanceOf(MqttException.class);
    }

    @Test
    void rejectsAnAnonymousConnectionOnTheWebSocketListener() {
        MqttConnectOptions options = new MqttConnectOptions();
        options.setConnectionTimeout(3);
        options.setAutomaticReconnect(false);
        String brokerUrl = "ws://" + mosquitto.getHost() + ":" + mosquitto.getMappedPort(9001);

        assertThatThrownBy(() -> connectAnonymously(brokerUrl, options)).isInstanceOf(MqttException.class);
    }

    private static void connectAnonymously(String brokerUrl, MqttConnectOptions options) throws MqttException {
        MqttClient client = new MqttClient(brokerUrl, "fleetpulse-anon-test-" + UUID.randomUUID(), new MemoryPersistence());
        try {
            client.connect(options);
        } finally {
            client.close();
        }
    }
}

package dev.fleetpulse.processor.simulator;

import dev.fleetpulse.processor.presence.PresencePayload;
import dev.fleetpulse.processor.telemetry.TelemetryPayload;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Random;
import java.util.UUID;

// Task 5.3: one simulated device end to end. Registers the two-step
// presence contract PresenceMqttConfig documents (task 5.1's Javadoc: set
// the will BEFORE connect(), then publish the retained online announcement
// immediately after connect() succeeds) and publishes periodic telemetry on
// the WU3 topic and payload shape via the SAME TelemetryPayload record the
// real ingest pipeline deserializes into. Exactly one long-lived IMqttClient
// per instance, created once in the constructor and never recreated -- that,
// plus the single mutable SimulatedVehicleState field reassigned every tick,
// is what keeps one vehicle's own memory footprint fixed regardless of how
// many ticks it has published (DoD: no monotonic memory growth).
public final class SimulatedVehicle {

    static final int TELEMETRY_QOS = 0;
    static final int STATUS_QOS = 1;

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final String telemetryTopic;
    private final String statusTopic;
    private final MqttClient client;
    private SimulatedVehicleState state;
    private int publishedCount;

    public SimulatedVehicle(String orgId, UUID vehicleId, String brokerUrl, String username, String password) throws MqttException {
        this.telemetryTopic = "fleet/%s/vehicle/%s/telemetry".formatted(orgId, vehicleId);
        this.statusTopic = "fleet/%s/vehicle/%s/status".formatted(orgId, vehicleId);
        this.client = new MqttClient(brokerUrl, "fleetpulse-simulator-" + vehicleId, new MemoryPersistence());
        connect(username, password);
    }

    private void connect(String username, String password) throws MqttException {
        byte[] offlineWillPayload = serialize(new PresencePayload(false));
        MqttConnectOptions options = DeviceConnectOptionsFactory.create(username, password, statusTopic, offlineWillPayload, STATUS_QOS);
        client.connect(options);
        publishRetained(statusTopic, new PresencePayload(true), STATUS_QOS);
    }

    public void publishNextTelemetry(TelemetrySampleGenerator generator, Random random) throws MqttException {
        state = generator.next(state, random);
        TelemetryPayload payload = generator.toPayload(state, Instant.now());
        MqttMessage message = new MqttMessage(serialize(payload));
        message.setQos(TELEMETRY_QOS);
        client.publish(telemetryTopic, message);
        publishedCount++;
    }

    // Mirrors WU8's PresenceEndToEndTest: the 3-arg disconnectForcibly
    // overload with sendDisconnectPacket=false skips the clean MQTT
    // DISCONNECT packet, which is exactly what makes the broker treat the
    // drop as abnormal and publish this device's registered will (DoD #2).
    // No offline-marking code runs here on purpose -- that is the broker's
    // job, not this simulator's.
    public void disconnectAbruptly() throws MqttException {
        client.disconnectForcibly(0L, 0L, false);
    }

    public void close() throws MqttException {
        if (client.isConnected()) {
            client.disconnect();
        }
        client.close();
    }

    public int publishedCount() {
        return publishedCount;
    }

    private void publishRetained(String topic, PresencePayload payload, int qos) throws MqttException {
        MqttMessage message = new MqttMessage(serialize(payload));
        message.setQos(qos);
        message.setRetained(true);
        client.publish(topic, message);
    }

    private byte[] serialize(Object value) {
        return jsonMapper.writeValueAsString(value).getBytes(StandardCharsets.UTF_8);
    }
}

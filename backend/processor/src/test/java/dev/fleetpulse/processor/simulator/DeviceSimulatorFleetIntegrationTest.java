package dev.fleetpulse.processor.simulator;

import dev.fleetpulse.processor.mqtt.SecuredMosquittoTestSupport;
import dev.fleetpulse.processor.telemetry.TelemetryMessage;
import dev.fleetpulse.processor.telemetry.TelemetryPayloadParser;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

// Task 5.3: proves SimulatedVehicle/DeviceSimulatorFleet against a real,
// secured Mosquitto broker -- only Mosquitto, no PostGIS. The simulator is a
// pure MQTT publisher; WU8's PresenceEndToEndTest already proved the
// consumer side of the LWT contract these tests register against, so no
// production consumer runs here -- each test subscribes directly to observe
// exactly what the simulator itself publishes.
@Testcontainers
class DeviceSimulatorFleetIntegrationTest {

    @Container
    static final GenericContainer<?> mosquitto = SecuredMosquittoTestSupport.newContainer();

    private static final String ORG_ID = "org-1";

    private MqttClient subscriber;
    private DeviceSimulatorFleet fleet;

    @AfterEach
    void tearDown() throws Exception {
        if (fleet != null) {
            fleet.close();
        }
        if (subscriber != null) {
            if (subscriber.isConnected()) {
                subscriber.disconnect();
            }
            subscriber.close();
        }
    }

    // Task 5.1 step 2: connecting a device must publish a retained online
    // announcement immediately, so a client subscribing afterward still
    // receives it right away instead of waiting for the next tick.
    @Test
    void connectingAVehiclePublishesARetainedOnlineAnnouncement() throws Exception {
        UUID vehicleId = UUID.randomUUID();
        SimulatedVehicle vehicle = newVehicle(vehicleId);
        fleet = new DeviceSimulatorFleet(List.of(vehicle), Duration.ofMillis(200));

        List<String> received = subscribeAndCapture(statusTopic(vehicleId));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(received).contains("{\"online\":true}"));
    }

    // WU3 wire contract: telemetry the simulator publishes must parse
    // through the REAL production TelemetryPayloadParser.
    @Test
    void publishedTelemetryIsParseableByTheRealTelemetryPayloadParser() throws Exception {
        UUID vehicleId = UUID.randomUUID();
        SimulatedVehicle vehicle = newVehicle(vehicleId);
        fleet = new DeviceSimulatorFleet(List.of(vehicle), Duration.ofMillis(200));
        List<String> received = subscribeAndCapture(telemetryTopic(vehicleId));

        vehicle.publishNextTelemetry(new TelemetrySampleGenerator(), new Random(1));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(received).isNotEmpty());
        TelemetryMessage parsed = new TelemetryPayloadParser().parse(telemetryTopic(vehicleId), received.get(0));
        assertThat(parsed.vehicleId()).isEqualTo(vehicleId);
        assertThat(parsed.lat()).isBetween(-90.0, 90.0);
        assertThat(parsed.lon()).isBetween(-180.0, 180.0);
    }

    // DoD #2: abruptly cutting a simulated device must let the broker's own
    // Last Will mechanism mark it offline, with zero simulator-side cleanup
    // code -- the same disconnectForcibly(0,0,false) trick WU8's
    // PresenceEndToEndTest (test 6.6) used to genuinely trigger the broker's
    // will publish, proven here from the registering side.
    @Test
    void abruptlyCuttingAVehiclePublishesTheRegisteredOfflineWill() throws Exception {
        UUID vehicleId = UUID.randomUUID();
        SimulatedVehicle vehicle = newVehicle(vehicleId);
        fleet = new DeviceSimulatorFleet(List.of(vehicle), Duration.ofMillis(200));
        List<String> received = subscribeAndCapture(statusTopic(vehicleId));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(received).contains("{\"online\":true}"));

        vehicle.disconnectAbruptly();

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(received).contains("{\"online\":false}"));
    }

    // Structural bounded-state evidence for DoD #1: ticking a fleet many
    // times must never grow the vehicle list, and every vehicle's own
    // publish counter must land on exactly the number of ticks run -- proves
    // tickAll() reassigns each vehicle's state instead of accumulating it,
    // deterministically and without a real-time wait. A genuine, real-time
    // 50-vehicle soak run was additionally performed manually; see this work
    // unit's apply-progress and final report for the observed numbers.
    @Test
    void tickingManyTimesKeepsFleetSizeConstantAndAccountsForEveryTick() throws Exception {
        int vehicleCount = 10;
        int tickCount = 300;
        List<SimulatedVehicle> vehicles = new ArrayList<>();
        for (int i = 0; i < vehicleCount; i++) {
            vehicles.add(newVehicle(UUID.randomUUID()));
        }
        fleet = new DeviceSimulatorFleet(vehicles, Duration.ofMillis(50));

        for (int i = 0; i < tickCount; i++) {
            fleet.tickAll();
        }

        assertThat(fleet.vehicles()).hasSize(vehicleCount);
        assertThat(fleet.vehicles()).allSatisfy(vehicle -> assertThat(vehicle.publishedCount()).isEqualTo(tickCount));
    }

    private SimulatedVehicle newVehicle(UUID vehicleId) throws Exception {
        return new SimulatedVehicle(
            ORG_ID, vehicleId, brokerUrl(), SecuredMosquittoTestSupport.SERVICE_USERNAME, SecuredMosquittoTestSupport.SERVICE_PASSWORD
        );
    }

    private List<String> subscribeAndCapture(String topic) throws Exception {
        subscriber = new MqttClient(brokerUrl(), "fleetpulse-test-subscriber-" + UUID.randomUUID(), new MemoryPersistence());
        MqttConnectOptions options = new MqttConnectOptions();
        options.setConnectionTimeout(3);
        options.setAutomaticReconnect(false);
        options.setUserName(SecuredMosquittoTestSupport.SERVICE_USERNAME);
        options.setPassword(SecuredMosquittoTestSupport.SERVICE_PASSWORD.toCharArray());
        subscriber.connect(options);
        CopyOnWriteArrayList<String> received = new CopyOnWriteArrayList<>();
        subscriber.subscribe(topic, 1, (receivedTopic, message) ->
            received.add(new String(message.getPayload(), StandardCharsets.UTF_8)));
        return received;
    }

    private static String brokerUrl() {
        return "tcp://" + mosquitto.getHost() + ":" + mosquitto.getMappedPort(1883);
    }

    private static String statusTopic(UUID vehicleId) {
        return "fleet/" + ORG_ID + "/vehicle/" + vehicleId + "/status";
    }

    private static String telemetryTopic(UUID vehicleId) {
        return "fleet/" + ORG_ID + "/vehicle/" + vehicleId + "/telemetry";
    }
}

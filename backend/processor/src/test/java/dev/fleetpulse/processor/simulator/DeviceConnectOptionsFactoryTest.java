package dev.fleetpulse.processor.simulator;

import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

// Task 5.3: pure construction of the MqttConnectOptions a simulated device
// must use to satisfy PresenceMqttConfig's two-step registration contract
// (task 5.1's Javadoc, step 1) -- the will is set on these options BEFORE
// connect() is ever called; Paho sends it as part of the CONNECT packet
// itself. No I/O here, so the contract is provable without a broker.
class DeviceConnectOptionsFactoryTest {

    private static final String STATUS_TOPIC = "fleet/org-1/vehicle/veh-1/status";
    private static final byte[] OFFLINE_WILL_PAYLOAD = "{\"online\":false}".getBytes(StandardCharsets.UTF_8);

    @Test
    void registersTheOfflineWillWithQosOneAndRetainedBeforeConnectIsEverCalled() {
        MqttConnectOptions options = DeviceConnectOptionsFactory.create(null, null, STATUS_TOPIC, OFFLINE_WILL_PAYLOAD, 1);

        assertThat(options.getWillDestination()).isEqualTo(STATUS_TOPIC);
        assertThat(options.getWillMessage().getPayload()).isEqualTo(OFFLINE_WILL_PAYLOAD);
        assertThat(options.getWillMessage().getQos()).isEqualTo(1);
        assertThat(options.getWillMessage().isRetained()).isTrue();
        assertThat(options.isCleanSession()).isTrue();
        assertThat(options.isAutomaticReconnect()).isTrue();
    }

    @Test
    void leavesCredentialsUnsetWhenUsernameIsBlank() {
        MqttConnectOptions options = DeviceConnectOptionsFactory.create("", "irrelevant", STATUS_TOPIC, OFFLINE_WILL_PAYLOAD, 1);

        assertThat(options.getUserName()).isNull();
    }

    // Triangulation: a real username/password pair must actually be applied,
    // not silently dropped the way the blank case above is -- proves the
    // StringUtils.hasText branch really runs both ways.
    @Test
    void appliesCredentialsWhenUsernameIsPresent() {
        MqttConnectOptions options = DeviceConnectOptionsFactory.create("device-1", "s3cr3t", STATUS_TOPIC, OFFLINE_WILL_PAYLOAD, 1);

        assertThat(options.getUserName()).isEqualTo("device-1");
        assertThat(options.getPassword()).isEqualTo("s3cr3t".toCharArray());
    }
}

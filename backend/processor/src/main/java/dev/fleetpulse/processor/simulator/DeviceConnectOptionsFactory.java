package dev.fleetpulse.processor.simulator;

import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.springframework.util.StringUtils;

// Task 5.3: pure construction of the MqttConnectOptions a simulated device
// must use to satisfy PresenceMqttConfig's two-step registration contract
// (task 5.1's Javadoc): step 1, register the will BEFORE connect() -- there
// is no separate broker call for this, Paho sends it as part of the CONNECT
// packet. SimulatedVehicle is what actually calls connect() with the
// options this returns and then performs step 2 (publish the retained
// online announcement).
public final class DeviceConnectOptionsFactory {

    private DeviceConnectOptionsFactory() {
    }

    public static MqttConnectOptions create(
        String username, String password, String statusTopic, byte[] offlineWillPayload, int statusQos
    ) {
        MqttConnectOptions options = new MqttConnectOptions();
        options.setAutomaticReconnect(true);
        options.setCleanSession(true);
        if (StringUtils.hasText(username)) {
            options.setUserName(username);
            options.setPassword(password == null ? new char[0] : password.toCharArray());
        }
        options.setWill(statusTopic, offlineWillPayload, statusQos, true);
        return options;
    }
}

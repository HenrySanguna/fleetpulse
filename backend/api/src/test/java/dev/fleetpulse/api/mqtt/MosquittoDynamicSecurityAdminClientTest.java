package dev.fleetpulse.api.mqtt;

import dev.fleetpulse.api.config.FleetpulseMqttDynsecProperties;
import dev.fleetpulse.api.config.FleetpulseMqttProperties;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.junit.jupiter.api.Test;
import org.springframework.integration.mqtt.core.DefaultMqttPahoClientFactory;
import org.springframework.integration.mqtt.core.MqttPahoClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Task 5.1: proves MosquittoDynamicSecurityAdminClient's commands actually
// take effect on a real, dynamic-security-secured Mosquitto broker (not a
// mock) -- createClient/createRole/addSubscribeAcl/addClientRole let a
// freshly-provisioned client connect and subscribe only where allowed, and
// deleteClient revokes it.
@Testcontainers
class MosquittoDynamicSecurityAdminClientTest {

    @Container
    static final GenericContainer<?> mosquitto = SecuredMosquittoTestSupport.newContainer();

    @Test
    void provisionsAClientThatCanSubscribeOnlyToItsAllowedTopicPattern() throws Exception {
        MosquittoDynamicSecurityAdminClient admin = newAdminClient();
        String username = "dispatcher-" + UUID.randomUUID();
        String password = "pw-" + UUID.randomUUID();
        String roleName = "role-" + UUID.randomUUID();

        admin.createClient(username, password);
        admin.createRoleIfMissing(roleName);
        admin.addSubscribeAcl(roleName, "fleet/org-a/#", true);
        admin.addClientRole(username, roleName);

        MqttClient allowedSubscriber = rawClient();
        try {
            allowedSubscriber.connect(rawConnectOptions(username, password));
            // A denied subscribe throws; an allowed one must not.
            allowedSubscriber.subscribe("fleet/org-a/vehicle/1/telemetry", 0);
        } finally {
            allowedSubscriber.disconnect();
            allowedSubscriber.close();
        }

        MqttClient deniedSubscriber = rawClient();
        try {
            deniedSubscriber.connect(rawConnectOptions(username, password));
            assertThatThrownBy(() -> deniedSubscriber.subscribe("fleet/org-b/vehicle/1/telemetry", 0))
                .isInstanceOf(MqttException.class);
        } finally {
            deniedSubscriber.disconnect();
            deniedSubscriber.close();
        }
    }

    @Test
    void createRoleIfMissingIsIdempotent() {
        MosquittoDynamicSecurityAdminClient admin = newAdminClient();
        String roleName = "role-" + UUID.randomUUID();

        admin.createRoleIfMissing(roleName);

        assertThatCode(() -> admin.createRoleIfMissing(roleName)).doesNotThrowAnyException();
    }

    @Test
    void deletedClientCanNoLongerConnect() throws Exception {
        MosquittoDynamicSecurityAdminClient admin = newAdminClient();
        String username = "device-" + UUID.randomUUID();
        String password = "pw-" + UUID.randomUUID();
        admin.createClient(username, password);

        admin.deleteClient(username);

        MqttClient client = rawClient();
        try {
            assertThatThrownBy(() -> client.connect(rawConnectOptions(username, password)))
                .isInstanceOf(MqttException.class);
        } finally {
            client.close();
        }
    }

    @Test
    void wrapsBrokerRejectionsInADynamicSecurityException() {
        MosquittoDynamicSecurityAdminClient admin = newAdminClient();

        assertThatThrownBy(() -> admin.addClientRole("no-such-client-" + UUID.randomUUID(), "no-such-role-" + UUID.randomUUID()))
            .isInstanceOf(MosquittoDynamicSecurityException.class);
    }

    private static MosquittoDynamicSecurityAdminClient newAdminClient() {
        return new MosquittoDynamicSecurityAdminClient(
            testClientFactory(),
            new FleetpulseMqttProperties(brokerUrl()),
            new FleetpulseMqttDynsecProperties(SecuredMosquittoTestSupport.ADMIN_USERNAME, SecuredMosquittoTestSupport.ADMIN_PASSWORD));
    }

    private static MqttClient rawClient() throws MqttException {
        return new MqttClient(brokerUrl(), "fleetpulse-test-" + UUID.randomUUID(), new MemoryPersistence());
    }

    private static MqttConnectOptions rawConnectOptions(String username, String password) {
        MqttConnectOptions options = new MqttConnectOptions();
        options.setConnectionTimeout(3);
        options.setAutomaticReconnect(false);
        options.setUserName(username);
        options.setPassword(password.toCharArray());
        return options;
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

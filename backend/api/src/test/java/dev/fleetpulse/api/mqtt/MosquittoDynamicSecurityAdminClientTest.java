package dev.fleetpulse.api.mqtt;

import dev.fleetpulse.api.config.FleetpulseMqttDynsecProperties;
import dev.fleetpulse.api.config.FleetpulseMqttProperties;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.junit.jupiter.api.Test;
import org.springframework.integration.mqtt.core.DefaultMqttPahoClientFactory;
import org.springframework.integration.mqtt.core.MqttPahoClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
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

    // Task 4.1: publish ACLs are the device-credential counterpart to
    // addSubscribeAcl -- a client with a publishClientSend grant on exactly
    // one topic must have its messages delivered on that topic and silently
    // dropped everywhere else. MQTT 3.1.1 gives publishers no error for an
    // ACL-denied publish (PUBACK looks identical either way, confirmed
    // empirically against a real broker before writing this test), so the
    // only reliable proof is whether a privileged subscriber actually
    // receives the message.
    @Test
    void publishAclOnlyDeliversMessagesToTheGrantedTopic() throws Exception {
        MosquittoDynamicSecurityAdminClient admin = newAdminClient();
        String username = "device-" + UUID.randomUUID();
        String password = "pw-" + UUID.randomUUID();
        String roleName = "role-" + UUID.randomUUID();
        String allowedTopic = "fleet/org-a/vehicle/v1/telemetry";
        String deniedTopic = "fleet/org-a/vehicle/v2/telemetry";

        admin.createClient(username, password);
        admin.createRoleIfMissing(roleName);
        admin.addPublishAcl(roleName, allowedTopic, true);
        admin.addClientRole(username, roleName);

        CountDownLatch allowedLatch = new CountDownLatch(1);
        CountDownLatch deniedLatch = new CountDownLatch(1);
        MqttClient monitor = rawClient();
        MqttClient publisher = rawClient();
        try {
            monitor.setCallback(topicLatchCallback(allowedTopic, allowedLatch, deniedTopic, deniedLatch));
            monitor.connect(rawConnectOptions(SecuredMosquittoTestSupport.SERVICE_USERNAME, SecuredMosquittoTestSupport.SERVICE_PASSWORD));
            monitor.subscribe("fleet/org-a/vehicle/+/telemetry", 1);

            publisher.connect(rawConnectOptions(username, password));
            publisher.publish(allowedTopic, "ok".getBytes(), 1, false);
            assertThat(allowedLatch.await(3, TimeUnit.SECONDS)).as("the allowed publish must be delivered").isTrue();

            publisher.publish(deniedTopic, "denied".getBytes(), 1, false);
            assertThat(deniedLatch.await(2, TimeUnit.SECONDS)).as("the denied publish must never be delivered").isFalse();
        } finally {
            publisher.disconnect();
            publisher.close();
            monitor.disconnect();
            monitor.close();
        }
    }

    // Both addSubscribeAcl and addPublishAcl must tolerate re-registering the
    // exact same rule: a vehicle-scoped role (task 4.1) is shared across
    // every credential ever issued for that vehicle (initial provisioning,
    // and each later rotation), so a second registration of an ACL that
    // already exists must not be treated as a failure -- mirrors
    // createRoleIfMissingIsIdempotent above.
    @Test
    void reRegisteringTheSameRoleAclIsIdempotent() {
        MosquittoDynamicSecurityAdminClient admin = newAdminClient();
        String roleName = "role-" + UUID.randomUUID();
        admin.createRoleIfMissing(roleName);

        admin.addPublishAcl(roleName, "fleet/org-a/vehicle/v1/telemetry", true);
        admin.addSubscribeAcl(roleName, "fleet/org-a/vehicle/v1/command", true);

        assertThatCode(() -> admin.addPublishAcl(roleName, "fleet/org-a/vehicle/v1/telemetry", true))
            .doesNotThrowAnyException();
        assertThatCode(() -> admin.addSubscribeAcl(roleName, "fleet/org-a/vehicle/v1/command", true))
            .doesNotThrowAnyException();
    }

    private static MqttCallback topicLatchCallback(String firstTopic, CountDownLatch firstLatch, String secondTopic, CountDownLatch secondLatch) {
        return new MqttCallback() {
            @Override
            public void connectionLost(Throwable cause) {
            }

            @Override
            public void messageArrived(String topic, MqttMessage message) {
                if (firstTopic.equals(topic)) {
                    firstLatch.countDown();
                } else if (secondTopic.equals(topic)) {
                    secondLatch.countDown();
                }
            }

            @Override
            public void deliveryComplete(IMqttDeliveryToken token) {
            }
        };
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

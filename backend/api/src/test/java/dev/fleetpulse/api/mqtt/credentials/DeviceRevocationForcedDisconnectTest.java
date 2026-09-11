package dev.fleetpulse.api.mqtt.credentials;

import dev.fleetpulse.api.mqtt.SecuredMosquittoTestSupport;
import dev.fleetpulse.api.security.DispatcherLoginTestSupport;
import dev.fleetpulse.domain.Device;
import dev.fleetpulse.domain.DeviceRepository;
import dev.fleetpulse.domain.Organization;
import dev.fleetpulse.domain.OrganizationRepository;
import dev.fleetpulse.domain.User;
import dev.fleetpulse.domain.UserRepository;
import dev.fleetpulse.domain.UserRole;
import dev.fleetpulse.domain.VehicleRepository;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Task 4.2 + spec scenario 6.5 -- "Revocación de un dispositivo conectado":
// revoking a device must cut its ACTIVE connection (not merely block future
// ones). Confirmed empirically before writing this test: Mosquitto's
// dynamic-security deleteClient command itself force-disconnects any live
// session under that username ("disconnected: administrative action" in the
// broker's own log) -- no separate disconnectClient command exists in this
// broker version, so DeviceCredentialService.revoke() only needs
// MosquittoDynamicSecurityAdminClient.deleteClient, already built in WU3.
@Testcontainers
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class DeviceRevocationForcedDisconnectTest {

    private static final Path POSTGIS_PARTMAN_DOCKERFILE = Path
        .of(System.getProperty("user.dir"), "..", "..", "docker", "postgis-partman", "Dockerfile")
        .normalize();

    @Container
    static final PostgreSQLContainer postgis = new PostgreSQLContainer(
        DockerImageName
            .parse(
                new ImageFromDockerfile("fleetpulse/postgis-partman:test", false)
                    .withDockerfile(POSTGIS_PARTMAN_DOCKERFILE)
                    .get()
            )
            .asCompatibleSubstituteFor("postgres")
    );

    @Container
    static final GenericContainer<?> mosquitto = SecuredMosquittoTestSupport.newContainer();

    @DynamicPropertySource
    static void backingServices(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgis::getJdbcUrl);
        registry.add("spring.datasource.username", postgis::getUsername);
        registry.add("spring.datasource.password", postgis::getPassword);
        registry.add("fleetpulse.mqtt.broker-url", () -> "tcp://" + mosquitto.getHost() + ":" + mosquitto.getMappedPort(1883));
        registry.add("fleetpulse.mqtt.dynsec.admin-username", () -> SecuredMosquittoTestSupport.ADMIN_USERNAME);
        registry.add("fleetpulse.mqtt.dynsec.admin-password", () -> SecuredMosquittoTestSupport.ADMIN_PASSWORD);
    }

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private OrganizationRepository organizations;

    @Autowired
    private UserRepository users;

    @Autowired
    private VehicleRepository vehicles;

    @Autowired
    private DeviceRepository devices;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void revokingAConnectedDeviceCutsItsActiveSessionAndBlocksReconnection() throws Exception {
        Organization org = organizations.save(new Organization("org-device-revoke-" + UUID.randomUUID()));
        users.save(new User(org, "admin-revoke@acme.test", passwordEncoder.encode("s3cret-pass"), UserRole.FLEET_ADMIN));
        Device device = DeviceCredentialTestSupport.createDeviceFixture(vehicles, devices, org, "Truck Revoke", "device-revoke");

        HttpHeaders adminSession = DispatcherLoginTestSupport.mutationHeadersFrom(
            DispatcherLoginTestSupport.login(restTemplate, baseUrl(), "admin-revoke@acme.test", "s3cret-pass"));
        DeviceCredentialResponse credentials = DeviceCredentialTestSupport.provision(restTemplate, baseUrl(), adminSession, device.getId());

        // GIVEN: a device with an actually-open MQTT connection, publishing telemetry.
        CountDownLatch disconnectedLatch = new CountDownLatch(1);
        MqttClient activeConnection = new MqttClient(brokerUrl(), "fleetpulse-revoke-test-" + UUID.randomUUID(), new MemoryPersistence());
        activeConnection.setCallback(new MqttCallback() {
            @Override
            public void connectionLost(Throwable cause) {
                disconnectedLatch.countDown();
            }

            @Override
            public void messageArrived(String topic, MqttMessage message) {
            }

            @Override
            public void deliveryComplete(IMqttDeliveryToken token) {
            }
        });
        String telemetryTopic = "fleet/" + org.getId() + "/vehicle/" + device.getVehicle().getId() + "/telemetry";
        activeConnection.connect(connectOptions(credentials.username(), credentials.password()));
        activeConnection.publish(telemetryTopic, "still-connected".getBytes(), 0, false);

        try {
            // WHEN: a fleet admin revokes the device's credentials, out-of-band from the open connection.
            ResponseEntity<Void> revokeResponse = restTemplate.exchange(
                baseUrl() + "/api/devices/" + device.getId() + "/mqtt-credentials",
                HttpMethod.DELETE,
                new HttpEntity<>(adminSession),
                Void.class);
            assertThat(revokeResponse.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

            // THEN: the existing connection is force-closed by the broker.
            assertThat(disconnectedLatch.await(10, TimeUnit.SECONDS))
                .as("revocation must force-disconnect the device's active broker session")
                .isTrue();
        } finally {
            if (activeConnection.isConnected()) {
                activeConnection.disconnect();
            }
            activeConnection.close();
        }

        // AND: any later reconnection attempt with those same (revoked) credentials is rejected.
        MqttClient reconnectAttempt = new MqttClient(brokerUrl(), "fleetpulse-revoke-retry-" + UUID.randomUUID(), new MemoryPersistence());
        try {
            assertThatThrownBy(() -> reconnectAttempt.connect(connectOptions(credentials.username(), credentials.password())))
                .as("a revoked device must not be able to reconnect with its old credentials")
                .isInstanceOf(MqttException.class);
        } finally {
            reconnectAttempt.close();
        }
    }

    private static MqttConnectOptions connectOptions(String username, String password) {
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

    private String baseUrl() {
        return "http://localhost:" + port;
    }
}

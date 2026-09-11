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
import dev.fleetpulse.domain.Vehicle;
import dev.fleetpulse.domain.VehicleRepository;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
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

// Task 4.1 + spec scenario 6.2 -- design.md's "Dispositivos: credenciales
// permanentes pero revocables": a device's ACL is scoped to its OWN vehicle
// only. Combines the positive case (own-vehicle telemetry is delivered) and
// the negative case (another vehicle's telemetry is silently dropped) in one
// test, mirroring WU3's CrossOrganizationMqttIsolationTest.
//
// MQTT 3.1.1 gives no client-side error for an ACL-denied publish (confirmed
// empirically before writing this test: PUBACK looks identical whether the
// broker delivered the message or silently dropped it), so both outcomes are
// observed the same way -- via a privileged subscriber watching for
// delivery/non-delivery, not via assertThatThrownBy on the publish call.
@Testcontainers
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class DeviceTelemetryVehicleIsolationTest {

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
    void aDeviceCanPublishToItsOwnVehicleButNotToAnothersVehicleTelemetry() throws Exception {
        Organization org = organizations.save(new Organization("org-device-isolation-" + UUID.randomUUID()));
        users.save(new User(org, "admin-isolation@acme.test", passwordEncoder.encode("s3cret-pass"), UserRole.FLEET_ADMIN));
        Device ownDevice = DeviceCredentialTestSupport.createDeviceFixture(vehicles, devices, org, "Truck A", "device-a");
        Vehicle otherVehicle = vehicles.save(new Vehicle(org, "Truck B"));

        HttpHeaders adminSession = DispatcherLoginTestSupport.mutationHeadersFrom(
            DispatcherLoginTestSupport.login(restTemplate, baseUrl(), "admin-isolation@acme.test", "s3cret-pass"));
        DeviceCredentialResponse credentials = DeviceCredentialTestSupport.provision(restTemplate, baseUrl(), adminSession, ownDevice.getId());

        String ownTopic = "fleet/" + org.getId() + "/vehicle/" + ownDevice.getVehicle().getId() + "/telemetry";
        String otherTopic = "fleet/" + org.getId() + "/vehicle/" + otherVehicle.getId() + "/telemetry";

        CountDownLatch ownDeliveredLatch = new CountDownLatch(1);
        CountDownLatch otherDeliveredLatch = new CountDownLatch(1);
        MqttClient monitor = new MqttClient(brokerUrl(), "fleetpulse-isolation-monitor-" + UUID.randomUUID(), new MemoryPersistence());
        MqttClient device = new MqttClient(brokerUrl(), "fleetpulse-isolation-device-" + UUID.randomUUID(), new MemoryPersistence());
        try {
            monitor.setCallback(topicLatchCallback(ownTopic, ownDeliveredLatch, otherTopic, otherDeliveredLatch));
            monitor.connect(connectOptions(SecuredMosquittoTestSupport.SERVICE_USERNAME, SecuredMosquittoTestSupport.SERVICE_PASSWORD));
            monitor.subscribe("fleet/" + org.getId() + "/vehicle/+/telemetry", 1);

            device.connect(connectOptions(credentials.username(), credentials.password()));

            // WHEN/THEN: publishing to its own vehicle's telemetry is delivered.
            device.publish(ownTopic, "own-telemetry".getBytes(), 1, false);
            assertThat(ownDeliveredLatch.await(5, TimeUnit.SECONDS))
                .as("telemetry published to the device's own vehicle must be delivered")
                .isTrue();

            // WHEN/THEN: publishing to another vehicle's telemetry is rejected.
            device.publish(otherTopic, "spoofed-telemetry".getBytes(), 1, false);
            assertThat(otherDeliveredLatch.await(2, TimeUnit.SECONDS))
                .as("the broker must reject telemetry published to another vehicle")
                .isFalse();
        } finally {
            device.disconnect();
            device.close();
            monitor.disconnect();
            monitor.close();
        }
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

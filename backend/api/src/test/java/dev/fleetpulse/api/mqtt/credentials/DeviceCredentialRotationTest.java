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
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Task 4.3 -- "Rotación de credencial sin dar de baja el dispositivo":
// rotating replaces a device's credential entirely (old ones stop working)
// while the Device row itself is never touched (it has no
// active/deactivated flag at all -- rotation only ever reads it, never
// deletes or modifies it).
@Testcontainers
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class DeviceCredentialRotationTest {

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
    void rotatingIssuesWorkingNewCredentialsInvalidatesTheOldOnesAndKeepsTheDeviceActive() throws Exception {
        Organization org = organizations.save(new Organization("org-device-rotate-" + UUID.randomUUID()));
        users.save(new User(org, "admin-rotate@acme.test", passwordEncoder.encode("s3cret-pass"), UserRole.FLEET_ADMIN));
        Device device = DeviceCredentialTestSupport.createDeviceFixture(vehicles, devices, org, "Truck Rotate", "device-rotate");

        HttpHeaders adminSession = DispatcherLoginTestSupport.mutationHeadersFrom(
            DispatcherLoginTestSupport.login(restTemplate, baseUrl(), "admin-rotate@acme.test", "s3cret-pass"));
        DeviceCredentialResponse oldCredentials = DeviceCredentialTestSupport.provision(restTemplate, baseUrl(), adminSession, device.getId());

        // WHEN: an admin rotates the device's credential.
        ResponseEntity<DeviceCredentialResponse> rotateResponse = restTemplate.exchange(
            baseUrl() + "/api/devices/" + device.getId() + "/mqtt-credentials/rotate",
            HttpMethod.POST,
            new HttpEntity<>(adminSession),
            DeviceCredentialResponse.class);

        assertThat(rotateResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        DeviceCredentialResponse newCredentials = rotateResponse.getBody();
        assertThat(newCredentials).isNotNull();
        assertThat(newCredentials.username()).isNotEqualTo(oldCredentials.username());

        // THEN: the old credentials no longer connect.
        MqttClient oldClient = new MqttClient(brokerUrl(), "fleetpulse-rotate-old-" + UUID.randomUUID(), new MemoryPersistence());
        try {
            assertThatThrownBy(() -> oldClient.connect(connectOptions(oldCredentials.username(), oldCredentials.password())))
                .as("the pre-rotation credentials must be invalidated")
                .isInstanceOf(MqttException.class);
        } finally {
            oldClient.close();
        }

        // AND: the new credentials work, scoped to the same vehicle as before.
        String telemetryTopic = "fleet/" + org.getId() + "/vehicle/" + device.getVehicle().getId() + "/telemetry";
        MqttClient newClient = new MqttClient(brokerUrl(), "fleetpulse-rotate-new-" + UUID.randomUUID(), new MemoryPersistence());
        try {
            newClient.connect(connectOptions(newCredentials.username(), newCredentials.password()));
            newClient.publish(telemetryTopic, "rotated".getBytes(), 1, false);
        } finally {
            newClient.disconnect();
            newClient.close();
        }

        // AND: the device itself was never deactivated or deleted by rotation.
        assertThat(devices.findById(device.getId())).isPresent();
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

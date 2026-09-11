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

import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Spec scenario 6.3: a device may only subscribe to its own vehicle's
// command topic -- design.md's device ACL never grants subscribe access to
// an organization's alerts topic, and the default dynsec ACL access for a
// roleless-for-that-topic authenticated client is deny (WU3, confirmed
// empirically), so no explicit deny rule is needed for this to hold.
@Testcontainers
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class DeviceAlertsSubscriptionRejectionTest {

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
    void aDeviceCannotSubscribeToItsOrganizationsAlertsTopic() throws Exception {
        Organization org = organizations.save(new Organization("org-device-alerts-" + UUID.randomUUID()));
        users.save(new User(org, "admin-alerts@acme.test", passwordEncoder.encode("s3cret-pass"), UserRole.FLEET_ADMIN));
        Device device = DeviceCredentialTestSupport.createDeviceFixture(vehicles, devices, org, "Truck Alerts", "device-alerts");

        HttpHeaders adminSession = DispatcherLoginTestSupport.mutationHeadersFrom(
            DispatcherLoginTestSupport.login(restTemplate, baseUrl(), "admin-alerts@acme.test", "s3cret-pass"));
        DeviceCredentialResponse credentials = DeviceCredentialTestSupport.provision(restTemplate, baseUrl(), adminSession, device.getId());

        MqttClient client = new MqttClient(brokerUrl(), "fleetpulse-alerts-test-" + UUID.randomUUID(), new MemoryPersistence());
        try {
            client.connect(connectOptions(credentials.username(), credentials.password()));

            assertThatThrownBy(() -> client.subscribe("fleet/" + org.getId() + "/alerts", 0))
                .as("a device must never be able to subscribe to its organization's alerts topic")
                .isInstanceOf(MqttException.class);
        } finally {
            client.disconnect();
            client.close();
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

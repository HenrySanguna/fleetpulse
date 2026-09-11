package dev.fleetpulse.api.mqtt.credentials;

import dev.fleetpulse.api.mqtt.SecuredMosquittoTestSupport;
import dev.fleetpulse.api.security.DispatcherLoginTestSupport;
import dev.fleetpulse.domain.Organization;
import dev.fleetpulse.domain.OrganizationRepository;
import dev.fleetpulse.domain.User;
import dev.fleetpulse.domain.UserRepository;
import dev.fleetpulse.domain.UserRole;
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

// Task 3.3, spec scenario 6.4: an expired browser MQTT credential must be
// unusable for a NEW connection, once ExpiredMqttCredentialPurgeTask has
// reclaimed it. The purge task's @Scheduled method is invoked directly here
// (a common, accepted way to test scheduled logic) rather than waiting on
// its real fixedDelay, against a real broker -- not a mock.
@Testcontainers
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class ExpiredBrowserCredentialConnectionTest {

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
        registry.add("fleetpulse.mqtt.browser.ws-url", () -> "ws://" + mosquitto.getHost() + ":" + mosquitto.getMappedPort(9001));
        // Deliberately very short: this test issues real credentials, waits
        // past this TTL, then triggers the purge task directly.
        registry.add("fleetpulse.mqtt.browser.credential-ttl", () -> "PT1S");
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
    private PasswordEncoder passwordEncoder;

    @Autowired
    private ExpiredMqttCredentialPurgeTask purgeTask;

    @Test
    void expiredBrowserCredentialsCannotConnectAfterThePurgeTaskRuns() throws Exception {
        Organization org = organizations.save(new Organization("org-expiry-" + UUID.randomUUID()));
        String email = "dispatcher-expiry-" + UUID.randomUUID() + "@acme.test";
        users.save(new User(org, email, passwordEncoder.encode("s3cret-pass"), UserRole.DISPATCHER));

        ResponseEntity<String> loginResponse = DispatcherLoginTestSupport.login(restTemplate, baseUrl(), email, "s3cret-pass");
        HttpHeaders sessionHeaders = DispatcherLoginTestSupport.sessionHeadersFrom(loginResponse);

        ResponseEntity<MqttCredentialsResponse> credentialsResponse = restTemplate.exchange(
            baseUrl() + "/api/mqtt/credentials",
            HttpMethod.GET,
            new HttpEntity<>(sessionHeaders),
            MqttCredentialsResponse.class);
        assertThat(credentialsResponse.getBody()).isNotNull();
        MqttCredentialsResponse credentials = credentialsResponse.getBody();

        // GIVEN: credentials that have passed their expiresAt.
        Thread.sleep(1500);

        // (implicit WHEN of task 3.3): the purge cycle runs.
        purgeTask.purgeExpiredCredentials();

        // WHEN/THEN: a new connection attempt with those credentials is rejected.
        MqttClient client = new MqttClient(brokerUrl(), "fleetpulse-expiry-test-" + UUID.randomUUID(), new MemoryPersistence());
        try {
            assertThatThrownBy(() -> client.connect(connectOptions(credentials.username(), credentials.password())))
                .isInstanceOf(MqttException.class);
        } finally {
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

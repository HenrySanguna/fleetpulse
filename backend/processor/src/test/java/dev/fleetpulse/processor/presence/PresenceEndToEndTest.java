package dev.fleetpulse.processor.presence;

import dev.fleetpulse.processor.config.FleetpulseMqttProperties;
import dev.fleetpulse.processor.config.FleetpulseMqttServiceCredentialsProperties;
import dev.fleetpulse.processor.mqtt.SecuredMosquittoTestSupport;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.integration.config.EnableIntegration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

// Tests 6.6/6.7: a device that registers an MQTT Last Will and Testament at
// connect time (task 5.1's contract) must be marked offline once the broker
// publishes that will after an abrupt, non-clean disconnection, and marked
// online again once it reconnects and announces itself. Wires the real
// PresenceMqttConfig/PresencePayloadParser/PresenceMessageListener beans
// against a real, secured Mosquitto broker plus a real PostGIS database --
// the same dual-container convention TelemetryEndToEndIngestTest (WU5)
// established -- because presence is its own logically independent topic
// (tasks.md, WU8), not a variant of the telemetry pipeline.
@Testcontainers
class PresenceEndToEndTest {

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

    private static final String ORG_ID = "org-1";

    private AnnotationConfigApplicationContext context;
    private MqttClient device;

    @AfterEach
    void tearDown() throws Exception {
        if (device != null) {
            if (device.isConnected()) {
                device.disconnect();
            }
            device.close();
        }
        if (context != null) {
            context.close();
        }
    }

    // Test 6.6: an abrupt, non-clean disconnection must trigger the broker
    // to publish the device's registered will, and the presence consumer
    // must then mark the vehicle offline.
    @Test
    void abruptDisconnectionPublishesTheWillAndMarksVehicleOffline() throws Exception {
        migrate();
        context = startContext();
        UUID vehicleId = seedVehicle("Truck-WU8-1");
        connectDeviceWithRegisteredWill(vehicleId);
        announceOnline(vehicleId);

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
            assertThat(readOnline(vehicleId)).isTrue());

        // sendDisconnectPacket=false: the client tears down the TCP
        // connection without sending a clean MQTT DISCONNECT packet -- the
        // exact signal the broker needs to treat this as an abnormal
        // disconnection and publish the registered will. Every other
        // disconnectForcibly() overload defaults this to true, which would
        // suppress the will just like a graceful disconnect() would.
        device.disconnectForcibly(0L, 0L, false);

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
            assertThat(readOnline(vehicleId)).isFalse());
    }

    // Test 6.7: once offline, a device that reconnects and announces itself
    // online again must flip vehicle_state.online back.
    @Test
    void reconnectionMarksVehicleOnlineAgain() throws Exception {
        migrate();
        context = startContext();
        UUID vehicleId = seedVehicle("Truck-WU8-2");
        connectDeviceWithRegisteredWill(vehicleId);
        announceOnline(vehicleId);
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
            assertThat(readOnline(vehicleId)).isTrue());

        device.disconnectForcibly(0L, 0L, false);
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
            assertThat(readOnline(vehicleId)).isFalse());

        connectDeviceWithRegisteredWill(vehicleId);
        announceOnline(vehicleId);

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
            assertThat(readOnline(vehicleId)).isTrue());
    }

    private AnnotationConfigApplicationContext startContext() {
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        ctx.registerBean(FleetpulseMqttProperties.class, () -> new FleetpulseMqttProperties(brokerUrl()));
        ctx.registerBean(FleetpulseMqttServiceCredentialsProperties.class, () -> new FleetpulseMqttServiceCredentialsProperties(
            SecuredMosquittoTestSupport.SERVICE_USERNAME, SecuredMosquittoTestSupport.SERVICE_PASSWORD
        ));
        ctx.registerBean(MeterRegistry.class, SimpleMeterRegistry::new);
        ctx.registerBean(JdbcTemplate.class, () -> new JdbcTemplate(
            new DriverManagerDataSource(postgis.getJdbcUrl(), postgis.getUsername(), postgis.getPassword())
        ));
        // @EnableIntegration registers the MessagingAnnotationPostProcessor
        // that turns @ServiceActivator into an actual channel subscriber;
        // the real app gets this for free from Boot's IntegrationAutoConfiguration.
        ctx.register(
            IntegrationTestConfig.class, PresenceMqttConfig.class, PresencePayloadParser.class,
            JdbcVehiclePresenceWriter.class, PresenceMessageListener.class
        );
        ctx.refresh();
        return ctx;
    }

    @EnableIntegration
    @Configuration
    static class IntegrationTestConfig {
    }

    private static void migrate() {
        Flyway.configure().dataSource(postgis.getJdbcUrl(), postgis.getUsername(), postgis.getPassword()).load().migrate();
    }

    private static String brokerUrl() {
        return "tcp://" + mosquitto.getHost() + ":" + mosquitto.getMappedPort(1883);
    }

    private void connectDeviceWithRegisteredWill(UUID vehicleId) throws Exception {
        device = new MqttClient(brokerUrl(), "fleetpulse-test-device-" + UUID.randomUUID(), new MemoryPersistence());
        MqttConnectOptions options = new MqttConnectOptions();
        options.setConnectionTimeout(3);
        options.setAutomaticReconnect(false);
        options.setUserName(SecuredMosquittoTestSupport.SERVICE_USERNAME);
        options.setPassword(SecuredMosquittoTestSupport.SERVICE_PASSWORD.toCharArray());
        options.setWill(topic(vehicleId), "{\"online\":false}".getBytes(StandardCharsets.UTF_8), 1, true);
        device.connect(options);
    }

    private void announceOnline(UUID vehicleId) throws Exception {
        MqttMessage message = new MqttMessage("{\"online\":true}".getBytes(StandardCharsets.UTF_8));
        message.setQos(1);
        message.setRetained(true);
        device.publish(topic(vehicleId), message);
    }

    private static String topic(UUID vehicleId) {
        return "fleet/" + ORG_ID + "/vehicle/" + vehicleId + "/status";
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(postgis.getJdbcUrl(), postgis.getUsername(), postgis.getPassword());
    }

    private static Boolean readOnline(UUID vehicleId) throws SQLException {
        try (
            Connection connection = connect();
            PreparedStatement statement = connection.prepareStatement("SELECT online FROM vehicle_state WHERE vehicle_id = ?")
        ) {
            statement.setObject(1, vehicleId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                return resultSet.getBoolean("online");
            }
        }
    }

    private static UUID seedVehicle(String label) throws SQLException {
        try (Connection connection = connect()) {
            UUID organizationId = UUID.randomUUID();
            try (
                PreparedStatement statement = connection
                    .prepareStatement("INSERT INTO organizations (id, name, created_at) VALUES (?, ?, ?)")
            ) {
                statement.setObject(1, organizationId);
                statement.setString(2, "Org-" + label);
                statement.setTimestamp(3, Timestamp.from(Instant.now()));
                statement.executeUpdate();
            }
            UUID vehicleId = UUID.randomUUID();
            try (
                PreparedStatement statement = connection
                    .prepareStatement("INSERT INTO vehicles (id, organization_id, label, created_at) VALUES (?, ?, ?, ?)")
            ) {
                statement.setObject(1, vehicleId);
                statement.setObject(2, organizationId);
                statement.setString(3, label);
                statement.setTimestamp(4, Timestamp.from(Instant.now()));
                statement.executeUpdate();
            }
            return vehicleId;
        }
    }
}

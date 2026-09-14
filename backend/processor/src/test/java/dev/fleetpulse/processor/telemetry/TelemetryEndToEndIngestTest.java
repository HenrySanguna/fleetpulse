package dev.fleetpulse.processor.telemetry;

import dev.fleetpulse.processor.config.FleetpulseMqttProperties;
import dev.fleetpulse.processor.config.FleetpulseMqttServiceCredentialsProperties;
import dev.fleetpulse.processor.config.FleetpulseTelemetryBufferProperties;
import dev.fleetpulse.processor.config.FleetpulseTelemetryImplausibilityProperties;
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

// WU5: wiring only, connecting WU3's real MQTT consumer to WU4's
// implausibility filter, buffer, and batch writer through the real
// TelemetryMessageListener bean (no test-only shortcut) -- tests 6.1 and 6.4
// are the two scenarios that can only be honestly proven with a real broker
// (Mosquitto) and a real database (PostGIS) wired together, per the
// finalized work-unit forecast: TelemetryMqttConsumerTest (WU3) proves the
// consumer alone with no persistence, TelemetryBatchWriteTest (WU4) proves
// the filter/buffer/writer alone with no MQTT, and this file proves the
// whole pipeline end to end.
@Testcontainers
class TelemetryEndToEndIngestTest {

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
    private MqttClient publisher;

    @AfterEach
    void tearDown() throws Exception {
        if (publisher != null) {
            if (publisher.isConnected()) {
                publisher.disconnect();
            }
            publisher.close();
        }
        if (context != null) {
            context.close();
        }
    }

    // Test 6.1: the same MQTT message processed twice must produce exactly
    // one row in `positions`. The filter never rejects an exact resend (an
    // identical recordedAt is never "after" itself, so Geo.speedKmh reports
    // no implied speed -- see TelemetryImplausibilityFilter), so both copies
    // reach the buffer and the batch writer; the real guarantee is the
    // database's ON CONFLICT DO NOTHING on (vehicle_id, recorded_at), which
    // is exactly what this test proves end to end instead of assuming.
    @Test
    void sameMessageProcessedTwiceProducesExactlyOneRow() throws Exception {
        migrate();
        context = startContext(50, Duration.ofMillis(200));
        UUID vehicleId = seedVehicle("Truck-WU5-1");
        connectPublisher();
        warmUpUntilSubscribed(vehicleId);

        String payload = telemetryPayload(Instant.now(), 40.4, -3.7);
        publish(vehicleId, payload);
        publish(vehicleId, payload);

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
            assertThat(countPositions(vehicleId)).isEqualTo(1));

        // A late-arriving second row (e.g. a duplicate the filter should
        // have collapsed but the database did not) would appear shortly
        // after the first is observed; hold the assertion a moment longer.
        Thread.sleep(300);
        assertThat(countPositions(vehicleId)).isEqualTo(1);
    }

    // Test 6.4: a burst of 1,000 distinct positions for one vehicle must all
    // land, with zero duplicates and zero drops. A small buffer max-size
    // (200) forces several size-triggered flushes during the burst instead
    // of relying only on the time-based trigger, exercising task 3.1's
    // size-based path under real load.
    @Test
    void burstOfOneThousandDistinctPositionsLandsWithoutDuplicatesOrDrops() throws Exception {
        migrate();
        context = startContext(200, Duration.ofSeconds(5));
        UUID vehicleId = seedVehicle("Truck-WU5-2");
        connectPublisher();
        warmUpUntilSubscribed(vehicleId);

        Instant base = Instant.now();
        int total = 1000;
        for (int i = 0; i < total; i++) {
            publish(vehicleId, telemetryPayload(base.plusSeconds(i), 40.4, -3.7));
        }

        await().atMost(Duration.ofSeconds(60)).untilAsserted(() ->
            assertThat(countPositions(vehicleId)).isEqualTo(total));
    }

    // Warm-up: the Paho async client connects and subscribes after context
    // refresh completes, so retry a throwaway publish until it is actually
    // observed instead of guessing a fixed delay (same convention as
    // TelemetryMqttConsumerTest). The warm-up row is deleted afterward so it
    // does not pollute the measured row counts below, and it uses the same
    // lat/lon as the rest of that test so it never looks implausible to
    // TelemetryImplausibilityFilter regardless of how far in the past it is
    // dated.
    private void warmUpUntilSubscribed(UUID vehicleId) throws Exception {
        Instant warmupRecordedAt = Instant.now().minusSeconds(3600);
        for (int attempt = 1; attempt <= 10; attempt++) {
            publish(vehicleId, telemetryPayload(warmupRecordedAt, 40.4, -3.7));
            try {
                await().atMost(Duration.ofSeconds(2)).until(() -> countPositions(vehicleId) >= 1);
                deletePositions(vehicleId);
                return;
            } catch (org.awaitility.core.ConditionTimeoutException timedOut) {
                // Subscription likely was not active yet; retry with a fresh attempt.
            }
        }
        throw new AssertionError("Telemetry ingest pipeline never became ready to receive messages after 10 attempts");
    }

    private AnnotationConfigApplicationContext startContext(int bufferMaxSize, Duration flushInterval) {
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        ctx.registerBean(FleetpulseMqttProperties.class, () -> new FleetpulseMqttProperties(brokerUrl()));
        ctx.registerBean(FleetpulseMqttServiceCredentialsProperties.class, () -> new FleetpulseMqttServiceCredentialsProperties(
            SecuredMosquittoTestSupport.SERVICE_USERNAME, SecuredMosquittoTestSupport.SERVICE_PASSWORD
        ));
        ctx.registerBean(MeterRegistry.class, SimpleMeterRegistry::new);
        ctx.registerBean(FleetpulseTelemetryImplausibilityProperties.class, () -> new FleetpulseTelemetryImplausibilityProperties(300.0));
        ctx.registerBean(FleetpulseTelemetryBufferProperties.class, () -> new FleetpulseTelemetryBufferProperties(bufferMaxSize, flushInterval));
        ctx.registerBean(JdbcTemplate.class, () -> new JdbcTemplate(
            new DriverManagerDataSource(postgis.getJdbcUrl(), postgis.getUsername(), postgis.getPassword())
        ));
        // @EnableIntegration registers the MessagingAnnotationPostProcessor
        // that turns @ServiceActivator into an actual channel subscriber;
        // the real app gets this for free from Boot's IntegrationAutoConfiguration.
        ctx.register(
            IntegrationTestConfig.class, TelemetryMqttConfig.class, TelemetryPayloadParser.class,
            TelemetryImplausibilityFilter.class, JdbcTelemetryPositionWriter.class, TelemetryPositionBuffer.class,
            TelemetryMessageListener.class
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

    private void connectPublisher() throws Exception {
        publisher = new MqttClient(brokerUrl(), "fleetpulse-test-publisher-" + UUID.randomUUID(), new MemoryPersistence());
        MqttConnectOptions options = new MqttConnectOptions();
        options.setConnectionTimeout(3);
        options.setAutomaticReconnect(false);
        options.setUserName(SecuredMosquittoTestSupport.SERVICE_USERNAME);
        options.setPassword(SecuredMosquittoTestSupport.SERVICE_PASSWORD.toCharArray());
        publisher.connect(options);
    }

    private void publish(UUID vehicleId, String payload) throws Exception {
        MqttMessage message = new MqttMessage(payload.getBytes(StandardCharsets.UTF_8));
        message.setQos(0);
        publisher.publish(topic(vehicleId), message);
    }

    private static String telemetryPayload(Instant recordedAt, double lat, double lon) {
        return "{\"recordedAt\":\"" + recordedAt + "\",\"lat\":" + lat + ",\"lon\":" + lon + "}";
    }

    private static String topic(UUID vehicleId) {
        return "fleet/" + ORG_ID + "/vehicle/" + vehicleId + "/telemetry";
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(postgis.getJdbcUrl(), postgis.getUsername(), postgis.getPassword());
    }

    private static int countPositions(UUID vehicleId) throws SQLException {
        try (
            Connection connection = connect();
            PreparedStatement statement = connection.prepareStatement("SELECT count(*) FROM positions WHERE vehicle_id = ?")
        ) {
            statement.setObject(1, vehicleId);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).isTrue();
                return resultSet.getInt(1);
            }
        }
    }

    private static void deletePositions(UUID vehicleId) throws SQLException {
        try (
            Connection connection = connect();
            PreparedStatement statement = connection.prepareStatement("DELETE FROM positions WHERE vehicle_id = ?")
        ) {
            statement.setObject(1, vehicleId);
            statement.executeUpdate();
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

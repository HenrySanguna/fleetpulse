package dev.fleetpulse.processor.alerts;

import dev.fleetpulse.processor.config.FleetpulseAlertingProperties;
import dev.fleetpulse.processor.config.FleetpulseEtaProperties;
import dev.fleetpulse.processor.config.FleetpulseGeofencingProperties;
import dev.fleetpulse.processor.config.FleetpulseMotionDetectionProperties;
import dev.fleetpulse.processor.config.FleetpulseMqttProperties;
import dev.fleetpulse.processor.config.FleetpulseMqttServiceCredentialsProperties;
import dev.fleetpulse.processor.config.FleetpulseTelemetryBufferProperties;
import dev.fleetpulse.processor.config.FleetpulseTelemetryImplausibilityProperties;
import dev.fleetpulse.processor.eta.EtaPublisher;
import dev.fleetpulse.processor.eta.EtaRecalculationDispatcher;
import dev.fleetpulse.processor.eta.JdbcRecentSpeedReader;
import dev.fleetpulse.processor.eta.JdbcVehicleDestinationEtaWriter;
import dev.fleetpulse.processor.eta.JdbcVehicleDestinationReader;
import dev.fleetpulse.processor.eta.SinuosityEtaCalculator;
import dev.fleetpulse.processor.geofencing.AlertMqttConfig;
import dev.fleetpulse.processor.geofencing.GeofenceEvaluator;
import dev.fleetpulse.processor.geofencing.GeofenceRuleDispatcher;
import dev.fleetpulse.processor.geofencing.JdbcGeofenceAlertWriter;
import dev.fleetpulse.processor.geofencing.JdbcVehicleFenceStateWriter;
import dev.fleetpulse.processor.geofencing.MqttGeofenceAlertPublisher;
import dev.fleetpulse.processor.mqtt.SecuredMosquittoTestSupport;
import dev.fleetpulse.processor.telemetry.JdbcTelemetryPositionWriter;
import dev.fleetpulse.processor.telemetry.TelemetryImplausibilityFilter;
import dev.fleetpulse.processor.telemetry.TelemetryMessageListener;
import dev.fleetpulse.processor.telemetry.TelemetryMqttConfig;
import dev.fleetpulse.processor.telemetry.TelemetryPayloadParser;
import dev.fleetpulse.processor.telemetry.TelemetryPositionBuffer;
import dev.fleetpulse.processor.telemetry.VehicleMotionStreakTracker;
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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

// Tasks 3.2/3.3, test 5.6 ("exceso de velocidad sostenido genera una
// alerta, no una por posicion"): wired against a real PostGIS database and a
// real, secured Mosquitto broker -- the same dual-container convention
// GeofenceAlertEndToEndTest established, since speeding/excessive-idle
// detection is live-path (JdbcTelemetryPositionWriter's guarded write path),
// not the offline @Scheduled path trips segmentation uses. Stays
// deliberately minimal, the same way GeofenceAlertEndToEndTest's own class
// comment states: proves the WIRING (a real message reaches
// AlertRuleDispatcher through the real guarded path, and a fired alert is
// both persisted to `alerts` and published on fleet/{orgId}/alerts) and
// test 5.6's own dedup claim end to end -- AlertSilenceEngineTest already
// fully proves the dedup DECISION logic itself without any broker or
// database.
@Testcontainers
class AlertRuleEndToEndTest {

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

    private static final double DECOY_LAT = 40.4;
    private static final double DECOY_LON = -3.7;

    // A single, essentially stationary point: this test's own condition is
    // speeding (the message's own reported speedKmh), not real displacement
    // -- TelemetryImplausibilityFilter judges implied speed from lat/lon
    // movement, not the reported field, so keeping every position at the
    // same coordinates keeps every message comfortably plausible regardless
    // of the speedKmh value under test.
    private static final double VEHICLE_LAT = 4.65;
    private static final double VEHICLE_LON = -74.1;

    private static final double SPEED_LIMIT_KMH = 80.0;
    private static final double SPEEDING_KMH = 100.0;
    private static final double NORMAL_KMH = 40.0;

    private static final String TOPIC_ORG_SEGMENT = "org-1";

    private AnnotationConfigApplicationContext context;
    private MqttClient devicePublisher;
    private MqttClient alertSubscriber;

    @AfterEach
    void tearDown() throws Exception {
        if (devicePublisher != null) {
            if (devicePublisher.isConnected()) {
                devicePublisher.disconnect();
            }
            devicePublisher.close();
        }
        if (alertSubscriber != null) {
            if (alertSubscriber.isConnected()) {
                alertSubscriber.disconnect();
            }
            alertSubscriber.close();
        }
        if (context != null) {
            context.close();
        }
    }

    @Test
    void sustainedSpeedingAcrossManyPositionsProducesExactlyOneAlertNotOnePerPosition() throws Exception {
        migrate();
        // A silence window comfortably wider than this test's own trace
        // duration: the point under test is that a still-ongoing episode
        // does not repeat, not the window's own periodic-re-notification
        // half (AlertSilenceEngineTest already proves that separately).
        context = startContext(Duration.ofMinutes(15));
        UUID organizationId = insertOrganization("Acme Alert Rules Org 1");
        UUID vehicleId = insertVehicle(organizationId, "Truck-WU3-Speeding");

        connectDevicePublisher();
        warmUpUntilSubscribed();
        List<String> receivedAlerts = subscribeToAlerts(organizationId);

        Instant cursor = Instant.now();
        for (int i = 0; i < 6; i++) {
            publishTelemetry(vehicleId, telemetryPayload(cursor, SPEEDING_KMH));
            int expectedPositions = i + 1;
            await().atMost(Duration.ofSeconds(20)).until(() -> countPositions(vehicleId) >= expectedPositions);
            cursor = cursor.plusSeconds(10);
        }

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(countAlerts(vehicleId, "speeding")).isEqualTo(1));
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
            assertThat(receivedAlerts).anyMatch(payload ->
                payload.contains("\"type\":\"speeding\"") && payload.contains(vehicleId.toString())));
        Thread.sleep(500);

        assertThat(countAlerts(vehicleId, "speeding")).isEqualTo(1);
        assertThat(receivedAlerts).hasSize(1);
    }

    @Test
    void theConditionResolvingAndReoccurringProducesASecondAlert() throws Exception {
        migrate();
        context = startContext(Duration.ofMinutes(15));
        UUID organizationId = insertOrganization("Acme Alert Rules Org 2");
        UUID vehicleId = insertVehicle(organizationId, "Truck-WU3-Speeding-Resolve");

        connectDevicePublisher();
        warmUpUntilSubscribed();

        Instant cursor = Instant.now();
        publishTelemetry(vehicleId, telemetryPayload(cursor, SPEEDING_KMH));
        await().atMost(Duration.ofSeconds(20)).until(() -> countPositions(vehicleId) >= 1);
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(countAlerts(vehicleId, "speeding")).isEqualTo(1));

        cursor = cursor.plusSeconds(10);
        publishTelemetry(vehicleId, telemetryPayload(cursor, NORMAL_KMH));
        await().atMost(Duration.ofSeconds(20)).until(() -> countPositions(vehicleId) >= 2);
        Thread.sleep(300);
        assertThat(countAlerts(vehicleId, "speeding")).isEqualTo(1);

        cursor = cursor.plusSeconds(10);
        publishTelemetry(vehicleId, telemetryPayload(cursor, SPEEDING_KMH));
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(countAlerts(vehicleId, "speeding")).isEqualTo(2));
    }

    private AnnotationConfigApplicationContext startContext(Duration silenceWindow) {
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        ctx.registerBean(FleetpulseMqttProperties.class, () -> new FleetpulseMqttProperties(brokerUrl()));
        ctx.registerBean(FleetpulseMqttServiceCredentialsProperties.class, () -> new FleetpulseMqttServiceCredentialsProperties(
            SecuredMosquittoTestSupport.SERVICE_USERNAME, SecuredMosquittoTestSupport.SERVICE_PASSWORD
        ));
        ctx.registerBean(MeterRegistry.class, SimpleMeterRegistry::new);
        ctx.registerBean(FleetpulseTelemetryImplausibilityProperties.class, () -> new FleetpulseTelemetryImplausibilityProperties(300.0));
        ctx.registerBean(FleetpulseTelemetryBufferProperties.class,
            () -> new FleetpulseTelemetryBufferProperties(1, Duration.ofMillis(100)));
        ctx.registerBean(FleetpulseMotionDetectionProperties.class, () -> new FleetpulseMotionDetectionProperties(5.0, 12.0, Duration.ofSeconds(30)));
        // This test proves speeding, not geofencing/ETA -- no geofence is
        // ever seeded and no destination ever assigned, so both are wired
        // with no-op publishers, mirroring GeofenceAlertEndToEndTest's own
        // identical reasoning for the reverse case.
        ctx.registerBean(FleetpulseGeofencingProperties.class,
            () -> new FleetpulseGeofencingProperties(3, Duration.ofSeconds(30), 15.0, Duration.ofMinutes(10)));
        ctx.registerBean(FleetpulseEtaProperties.class, () -> new FleetpulseEtaProperties(1.3, 0.3, 5.0, 30.0, Duration.ofMinutes(15)));
        ctx.registerBean(EtaPublisher.class, () -> (organizationId, vehicleId, estimate, calculatedAt) -> { });
        ctx.registerBean(FleetpulseAlertingProperties.class,
            () -> new FleetpulseAlertingProperties(SPEED_LIMIT_KMH, Duration.ofMinutes(10), silenceWindow));
        ctx.registerBean(JdbcTemplate.class, () -> new JdbcTemplate(
            new DriverManagerDataSource(postgis.getJdbcUrl(), postgis.getUsername(), postgis.getPassword())
        ));
        ctx.register(
            IntegrationTestConfig.class, TelemetryMqttConfig.class, TelemetryPayloadParser.class,
            TelemetryImplausibilityFilter.class, VehicleMotionStreakTracker.class,
            GeofenceEvaluator.class, JdbcVehicleFenceStateWriter.class, JdbcGeofenceAlertWriter.class,
            AlertMqttConfig.class, MqttGeofenceAlertPublisher.class, GeofenceRuleDispatcher.class,
            JdbcVehicleDestinationReader.class, JdbcRecentSpeedReader.class, SinuosityEtaCalculator.class,
            JdbcVehicleDestinationEtaWriter.class, EtaRecalculationDispatcher.class,
            // The real writer/silence-state store AND the real MQTT publisher
            // (MqttAlertPublisher, reusing AlertMqttConfig registered above)
            // -- this is the one dispatcher this test actually exercises.
            JdbcAlertWriter.class, JdbcAlertSilenceStateStore.class, MqttAlertPublisher.class, AlertRuleDispatcher.class,
            JdbcTelemetryPositionWriter.class, TelemetryPositionBuffer.class, TelemetryMessageListener.class
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

    private void connectDevicePublisher() throws Exception {
        devicePublisher = new MqttClient(brokerUrl(), "fleetpulse-test-device-" + UUID.randomUUID(), new MemoryPersistence());
        MqttConnectOptions options = new MqttConnectOptions();
        options.setConnectionTimeout(3);
        options.setAutomaticReconnect(false);
        options.setUserName(SecuredMosquittoTestSupport.SERVICE_USERNAME);
        options.setPassword(SecuredMosquittoTestSupport.SERVICE_PASSWORD.toCharArray());
        devicePublisher.connect(options);
    }

    private List<String> subscribeToAlerts(UUID organizationId) throws Exception {
        alertSubscriber = new MqttClient(brokerUrl(), "fleetpulse-test-alert-subscriber-" + UUID.randomUUID(), new MemoryPersistence());
        MqttConnectOptions options = new MqttConnectOptions();
        options.setConnectionTimeout(3);
        options.setAutomaticReconnect(false);
        options.setUserName(SecuredMosquittoTestSupport.SERVICE_USERNAME);
        options.setPassword(SecuredMosquittoTestSupport.SERVICE_PASSWORD.toCharArray());
        alertSubscriber.connect(options);
        CopyOnWriteArrayList<String> received = new CopyOnWriteArrayList<>();
        alertSubscriber.subscribe("fleet/" + organizationId + "/alerts", 2, (topic, message) ->
            received.add(new String(message.getPayload(), StandardCharsets.UTF_8)));
        return received;
    }

    // Same technique GeofenceAlertEndToEndTest's own warmUpUntilSubscribed
    // uses -- see that class's comment for the full reasoning.
    private void warmUpUntilSubscribed() throws Exception {
        UUID warmupOrganizationId = insertOrganization("Warmup Org " + UUID.randomUUID());
        UUID warmupVehicleId = insertVehicle(warmupOrganizationId, "Warmup Vehicle");
        Instant warmupRecordedAt = Instant.now().minusSeconds(7200);
        for (int attempt = 1; attempt <= 10; attempt++) {
            publishTelemetry(warmupVehicleId, telemetryPayloadAt(warmupRecordedAt, DECOY_LAT, DECOY_LON, null));
            try {
                await().atMost(Duration.ofSeconds(2)).until(() -> countPositions(warmupVehicleId) >= 1);
                deletePositions(warmupVehicleId);
                return;
            } catch (org.awaitility.core.ConditionTimeoutException timedOut) {
                // Subscription likely was not active yet; retry with a fresh attempt.
            }
        }
        throw new AssertionError("Telemetry ingest pipeline never became ready to receive messages after 10 attempts");
    }

    private void publishTelemetry(UUID vehicleId, String payload) throws Exception {
        MqttMessage message = new MqttMessage(payload.getBytes(StandardCharsets.UTF_8));
        message.setQos(0);
        devicePublisher.publish(telemetryTopic(vehicleId), message);
    }

    private static String telemetryPayload(Instant recordedAt, double speedKmh) {
        return telemetryPayloadAt(recordedAt, VEHICLE_LAT, VEHICLE_LON, speedKmh);
    }

    private static String telemetryPayloadAt(Instant recordedAt, double lat, double lon, Double speedKmh) {
        String speedField = speedKmh == null ? "" : ",\"speedKmh\":" + speedKmh;
        return "{\"recordedAt\":\"" + recordedAt + "\",\"lat\":" + lat + ",\"lon\":" + lon + speedField + "}";
    }

    private static String telemetryTopic(UUID vehicleId) {
        return "fleet/" + TOPIC_ORG_SEGMENT + "/vehicle/" + vehicleId + "/telemetry";
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

    private static int countAlerts(UUID vehicleId, String alertType) throws SQLException {
        try (
            Connection connection = connect();
            PreparedStatement statement = connection
                .prepareStatement("SELECT count(*) FROM alerts WHERE vehicle_id = ? AND alert_type = ?")
        ) {
            statement.setObject(1, vehicleId);
            statement.setString(2, alertType);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).isTrue();
                return resultSet.getInt(1);
            }
        }
    }

    private static UUID insertOrganization(String name) throws SQLException {
        UUID id = UUID.randomUUID();
        try (
            Connection connection = connect();
            PreparedStatement statement = connection
                .prepareStatement("INSERT INTO organizations (id, name, created_at) VALUES (?, ?, ?)")
        ) {
            statement.setObject(1, id);
            statement.setString(2, name);
            statement.setTimestamp(3, Timestamp.from(Instant.now()));
            statement.executeUpdate();
        }
        return id;
    }

    private static UUID insertVehicle(UUID organizationId, String label) throws SQLException {
        UUID id = UUID.randomUUID();
        try (
            Connection connection = connect();
            PreparedStatement statement = connection
                .prepareStatement("INSERT INTO vehicles (id, organization_id, label, created_at) VALUES (?, ?, ?, ?)")
        ) {
            statement.setObject(1, id);
            statement.setObject(2, organizationId);
            statement.setString(3, label);
            statement.setTimestamp(4, Timestamp.from(Instant.now()));
            statement.executeUpdate();
        }
        return id;
    }
}

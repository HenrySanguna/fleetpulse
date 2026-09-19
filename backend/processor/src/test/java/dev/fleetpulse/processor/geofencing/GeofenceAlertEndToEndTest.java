package dev.fleetpulse.processor.geofencing;

import dev.fleetpulse.processor.alerts.AlertPublisher;
import dev.fleetpulse.processor.alerts.AlertRuleDispatcher;
import dev.fleetpulse.processor.alerts.JdbcAlertSilenceStateStore;
import dev.fleetpulse.processor.alerts.JdbcAlertWriter;
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
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

// Tasks 2.4/4.1-4.3 wired end to end against a real PostGIS database and a
// real, secured Mosquitto broker -- the same dual-container convention
// TelemetryEndToEndIngestTest (change 03, WU5) and PresenceEndToEndTest
// (WU8) established. Proves the WIRING exists and works: a real telemetry
// message reaches GeofenceEvaluator+FenceMembershipDetector+GeofenceRuleEngine
// through JdbcTelemetryPositionWriter's guarded write path, and a fired
// alert is both published on fleet/{orgId}/alerts at QoS 2 and persisted to
// geofence_alerts. The full 9-scenario spec proof (damping traces, dwell,
// overlap, restart-preserves-state, ...) is WU5's job, not this one -- this
// stays deliberately minimal: one clean-entry proof, one stale-telemetry
// proof of the task 2.4 guard.
@Testcontainers
class GeofenceAlertEndToEndTest {

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

    // Deliberately far from GEOFENCE_LON/GEOFENCE_LAT below, the same
    // Madrid-area decoy TelemetryEndToEndIngestTest uses for its own
    // subscription warm-up.
    private static final double DECOY_LAT = 40.4;
    private static final double DECOY_LON = -3.7;

    private static final double GEOFENCE_LAT = 4.71;
    private static final double GEOFENCE_LON = -74.07;
    private static final double INSIDE_LAT = 4.711;
    private static final double INSIDE_LON = -74.068;
    private static final double OUTSIDE_LAT = 4.711;
    private static final double OUTSIDE_LON = -73.0;

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
    void aCleanEntryPublishesAndPersistsExactlyOneEnterAlert() throws Exception {
        migrate();
        // confirmationReadings=1/confirmationDuration=ZERO: this test proves
        // the wiring reaches an alert, not WU3's own damping behavior
        // (already fully proven by FenceMembershipDetectorTest/
        // GeofenceRuleEngineTest without any broker or database).
        context = startContext(1, Duration.ofMillis(100), 1, Duration.ZERO);
        UUID organizationId = insertOrganization("Acme E2E Org 1");
        UUID vehicleId = insertVehicle(organizationId, "Truck-WU4-E2E-1");
        UUID geofenceId = insertSquareGeofence(organizationId, "Depot", GEOFENCE_LON, GEOFENCE_LAT, 0.01, "on_enter", null);

        connectDevicePublisher();
        warmUpUntilSubscribed();
        List<String> receivedAlerts = subscribeToAlerts(organizationId);

        publishTelemetry(vehicleId, telemetryPayload(Instant.now(), INSIDE_LAT, INSIDE_LON));

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
            assertThat(countAlerts(vehicleId, geofenceId, "enter")).isEqualTo(1));
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
            assertThat(receivedAlerts).anyMatch(payload ->
                payload.contains("\"type\":\"enter\"") && payload.contains(vehicleId.toString())));
        assertThat(readIsInside(vehicleId, geofenceId)).isTrue();
    }

    // Task 2.4's own wiring: a message older than the last one evaluated for
    // this vehicle must never re-run geofence evaluation, no matter what its
    // coordinates say -- the same "no retroactive alerts" rule design.md
    // states for telemetry desordenada, proven here at the wiring level
    // (WU5 proves it against the full 9-scenario spec).
    @Test
    void aStaleMessageWithOutsideCoordinatesNeverTriggersARetroactiveExitAlert() throws Exception {
        migrate();
        context = startContext(1, Duration.ofMillis(100), 1, Duration.ZERO);
        UUID organizationId = insertOrganization("Acme E2E Org 2");
        UUID vehicleId = insertVehicle(organizationId, "Truck-WU4-E2E-2");
        UUID geofenceId = insertSquareGeofence(organizationId, "Depot", GEOFENCE_LON, GEOFENCE_LAT, 0.01, "on_exit", null);
        Instant longAgo = Instant.now().minusSeconds(1200);
        seedVehicleFenceState(vehicleId, geofenceId, true, longAgo);

        connectDevicePublisher();
        warmUpUntilSubscribed();

        Instant baseline = Instant.now();
        publishTelemetry(vehicleId, telemetryPayload(baseline, INSIDE_LAT, INSIDE_LON));
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(countPositions(vehicleId)).isEqualTo(1));

        // Older than `baseline`, and geographically outside the geofence --
        // if the guard failed to reject this message, GeofenceRuleDispatcher
        // would see is_inside=true -> strict/buffered=false and confirm an
        // EXIT on the spot (confirmationReadings=1).
        publishTelemetry(vehicleId, telemetryPayload(baseline.minusSeconds(600), OUTSIDE_LAT, OUTSIDE_LON));
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(countPositions(vehicleId)).isEqualTo(2));

        // A retroactive exit alert would appear shortly after the second
        // position is observed; hold the assertion a moment longer than
        // just checking once immediately.
        Thread.sleep(500);
        assertThat(countAlerts(vehicleId, geofenceId, "exit")).isZero();
        assertThat(readIsInside(vehicleId, geofenceId)).isTrue();
    }

    private AnnotationConfigApplicationContext startContext(
        int bufferMaxSize, Duration flushInterval, int confirmationReadings, Duration confirmationDuration
    ) {
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        ctx.registerBean(FleetpulseMqttProperties.class, () -> new FleetpulseMqttProperties(brokerUrl()));
        ctx.registerBean(FleetpulseMqttServiceCredentialsProperties.class, () -> new FleetpulseMqttServiceCredentialsProperties(
            SecuredMosquittoTestSupport.SERVICE_USERNAME, SecuredMosquittoTestSupport.SERVICE_PASSWORD
        ));
        ctx.registerBean(MeterRegistry.class, SimpleMeterRegistry::new);
        ctx.registerBean(FleetpulseTelemetryImplausibilityProperties.class, () -> new FleetpulseTelemetryImplausibilityProperties(300.0));
        ctx.registerBean(FleetpulseTelemetryBufferProperties.class, () -> new FleetpulseTelemetryBufferProperties(bufferMaxSize, flushInterval));
        ctx.registerBean(FleetpulseMotionDetectionProperties.class, () -> new FleetpulseMotionDetectionProperties(5.0, 12.0, Duration.ofSeconds(30)));
        ctx.registerBean(FleetpulseGeofencingProperties.class,
            () -> new FleetpulseGeofencingProperties(confirmationReadings, confirmationDuration, 15.0));
        // Task 2.4 (06-add-trips-eta-alerts, WU2): no destinations are ever
        // assigned by this test, so EtaRecalculationDispatcher always finds
        // zero active destinations to recalculate -- this test proves
        // geofence alert dispatch, not ETA recalculation.
        ctx.registerBean(FleetpulseEtaProperties.class, () -> new FleetpulseEtaProperties(1.3, 0.3, 5.0, 30.0, Duration.ofMinutes(15)));
        ctx.registerBean(EtaPublisher.class, () -> (organizationId, vehicleId, estimate, calculatedAt) -> { });
        // Task 3.2/WU3: this test proves geofence alert dispatch, not
        // speeding/excessive-idle alerting -- no sustained condition is ever
        // produced here, so a no-op publisher is sufficient; the real
        // writer/silence-state store still run against the same database.
        ctx.registerBean(FleetpulseAlertingProperties.class,
            () -> new FleetpulseAlertingProperties(100.0, Duration.ofMinutes(10), Duration.ofMinutes(15)));
        ctx.registerBean(AlertPublisher.class, () -> alert -> { });
        ctx.registerBean(JdbcTemplate.class, () -> new JdbcTemplate(
            new DriverManagerDataSource(postgis.getJdbcUrl(), postgis.getUsername(), postgis.getPassword())
        ));
        // @EnableIntegration registers the MessagingAnnotationPostProcessor
        // that turns @ServiceActivator into an actual channel subscriber,
        // and starts every Lifecycle bean (including AlertMqttConfig's
        // outbound MqttPahoMessageHandler) synchronously during refresh().
        ctx.register(
            IntegrationTestConfig.class, TelemetryMqttConfig.class, TelemetryPayloadParser.class,
            TelemetryImplausibilityFilter.class, VehicleMotionStreakTracker.class,
            GeofenceEvaluator.class, JdbcVehicleFenceStateWriter.class, JdbcGeofenceAlertWriter.class,
            AlertMqttConfig.class, MqttGeofenceAlertPublisher.class, GeofenceRuleDispatcher.class,
            JdbcVehicleDestinationReader.class, JdbcRecentSpeedReader.class, SinuosityEtaCalculator.class,
            JdbcVehicleDestinationEtaWriter.class, EtaRecalculationDispatcher.class,
            JdbcAlertWriter.class, JdbcAlertSilenceStateStore.class, AlertRuleDispatcher.class,
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

    // Same technique TelemetryEndToEndIngestTest's own warmUpUntilSubscribed
    // uses: the MQTT inbound adapter's subscription is not guaranteed active
    // the instant context.refresh() returns, so a decoy message is published
    // and polled for until it lands, then discarded, before the real test
    // message is sent. Uses its OWN throwaway organization/vehicle,
    // deliberately never the vehicle under test: this decoy message would
    // otherwise be a real, eligible (first-ever) message for that vehicle,
    // and could itself run geofence evaluation against any geofence already
    // seeded for it (e.g. aStaleMessageWithOutsideCoordinatesNeverTriggersARetroactiveExitAlert's
    // pre-seeded vehicle_fence_state row) before the test's own assertions
    // ever run.
    private void warmUpUntilSubscribed() throws Exception {
        UUID warmupOrganizationId = insertOrganization("Warmup Org " + UUID.randomUUID());
        UUID warmupVehicleId = insertVehicle(warmupOrganizationId, "Warmup Vehicle");
        Instant warmupRecordedAt = Instant.now().minusSeconds(7200);
        for (int attempt = 1; attempt <= 10; attempt++) {
            publishTelemetry(warmupVehicleId, telemetryPayload(warmupRecordedAt, DECOY_LAT, DECOY_LON));
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

    private static String telemetryPayload(Instant recordedAt, double lat, double lon) {
        return "{\"recordedAt\":\"" + recordedAt + "\",\"lat\":" + lat + ",\"lon\":" + lon + "}";
    }

    // The topic's org segment is a caller-supplied routing label only --
    // TelemetryMessage never carries an organizationId parsed from it
    // (TelemetryPayloadParser's own contract, change 03). GeofenceRuleDispatcher
    // resolves the REAL organizationId from `vehicles` instead, which is why
    // this constant can legitimately differ from the real seeded
    // organization id used for the alerts topic/subscription above.
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

    // Retargeted at the unified `alerts` table (06-add-trips-eta-alerts/WU3,
    // task 3.1): geofence_id is now `context`, and alert_type carries the
    // "geofence_" prefix (GeofenceAlertType.storageValue()) -- callers still
    // pass the bare "enter"/"exit"/"dwell" value, prefixed here, so no call
    // site needed to change.
    private static int countAlerts(UUID vehicleId, UUID geofenceId, String alertType) throws SQLException {
        try (
            Connection connection = connect();
            PreparedStatement statement = connection.prepareStatement(
                "SELECT count(*) FROM alerts WHERE vehicle_id = ? AND context = ? AND alert_type = ?"
            )
        ) {
            statement.setObject(1, vehicleId);
            statement.setObject(2, geofenceId);
            statement.setString(3, "geofence_" + alertType);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).isTrue();
                return resultSet.getInt(1);
            }
        }
    }

    private static Boolean readIsInside(UUID vehicleId, UUID geofenceId) throws SQLException {
        try (
            Connection connection = connect();
            PreparedStatement statement = connection.prepareStatement(
                "SELECT is_inside FROM vehicle_fence_state WHERE vehicle_id = ? AND geofence_id = ?"
            )
        ) {
            statement.setObject(1, vehicleId);
            statement.setObject(2, geofenceId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                return resultSet.getBoolean("is_inside");
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

    private static UUID insertSquareGeofence(
        UUID organizationId, String name, double centerLon, double centerLat, double halfWidthDegrees, String rule, Integer dwellSecs
    ) throws SQLException {
        UUID id = UUID.randomUUID();
        double minLon = centerLon - halfWidthDegrees;
        double maxLon = centerLon + halfWidthDegrees;
        double minLat = centerLat - halfWidthDegrees;
        double maxLat = centerLat + halfWidthDegrees;
        String wkt = String.format(
            Locale.ROOT,
            "POLYGON((%f %f, %f %f, %f %f, %f %f, %f %f))",
            minLon, minLat, maxLon, minLat, maxLon, maxLat, minLon, maxLat, minLon, minLat
        );
        try (
            Connection connection = connect();
            PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO geofences (id, organization_id, name, area, rule, dwell_secs, is_active, created_at) "
                    + "VALUES (?, ?, ?, ST_GeomFromText(?, 4326)::geography, ?, ?, true, ?)"
            )
        ) {
            statement.setObject(1, id);
            statement.setObject(2, organizationId);
            statement.setString(3, name);
            statement.setString(4, wkt);
            statement.setString(5, rule);
            if (dwellSecs == null) {
                statement.setNull(6, java.sql.Types.INTEGER);
            } else {
                statement.setInt(6, dwellSecs);
            }
            statement.setTimestamp(7, Timestamp.from(Instant.now()));
            statement.executeUpdate();
        }
        return id;
    }

    private static void seedVehicleFenceState(UUID vehicleId, UUID geofenceId, boolean isInside, Instant since) throws SQLException {
        try (
            Connection connection = connect();
            PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO vehicle_fence_state (vehicle_id, geofence_id, is_inside, since) VALUES (?, ?, ?, ?)"
            )
        ) {
            statement.setObject(1, vehicleId);
            statement.setObject(2, geofenceId);
            statement.setBoolean(3, isInside);
            statement.setTimestamp(4, Timestamp.from(since));
            statement.executeUpdate();
        }
    }
}

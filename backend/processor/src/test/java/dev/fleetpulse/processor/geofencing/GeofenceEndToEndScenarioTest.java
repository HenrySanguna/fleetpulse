package dev.fleetpulse.processor.geofencing;

import dev.fleetpulse.geocore.Geo;
import dev.fleetpulse.geocore.GeoPoint;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

// Tests 6.1, 6.2, 6.6, 6.7, 6.8 + DoD "exactamente dos alertas" -- wired
// through the same real dual-container (PostGIS + Mosquitto) pipeline
// GeofenceAlertEndToEndTest (WU4) established, extended into the full
// spec-scenario proof WU4's own class comment deferred to this work unit.
// The realistic-noise oscillation proof (6.3/6.4/DoD "ruido realista") lives
// in its own file, GeofenceOscillationEndToEndTest -- unrelated noise-
// generation machinery, kept separate per file the same way
// TelemetryEndToEndIngestTest (WU5) and PresenceEndToEndTest (WU8) each
// stayed scoped to one concern in change 03.
@Testcontainers
class GeofenceEndToEndScenarioTest {

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

    private static final double GEOFENCE_LAT = 4.71;
    private static final double CENTER_LON = -74.07;
    private static final double HALF_WIDTH_DEG = 0.005;
    private static final double INSIDE_LAT = GEOFENCE_LAT;
    private static final double INSIDE_LON = CENTER_LON;

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

    // Tests 6.1 + 6.2 + DoD "un vehiculo simulado que cruza una geocerca
    // genera exactamente dos alertas (entrada y salida), ni una mas". One
    // geofence area is registered twice -- once on_enter, once on_exit,
    // exactly like a real deployment would configure two rules over the
    // same depot boundary -- and a single continuous 9-point trace drives
    // the vehicle from well outside, through the crossing, and well outside
    // again on the far side. Most of the 9 points are deliberately NOT at
    // the crossing instant (three clearly outside before, three clearly
    // inside/at the boundary, three clearly outside after): the "ni una
    // mas" half of the DoD is about proving a message that does not change
    // membership never refires an alert, not just that the two crossing
    // instants alone produce two alerts.
    @Test
    void aRealisticMultiPointCrossingGeneratesExactlyOneEntryAndOneExitAlert() throws Exception {
        migrate();
        context = startContext(1, Duration.ofMillis(100), 1, Duration.ZERO);
        UUID organizationId = insertOrganization("Acme Crossing Org");
        UUID vehicleId = insertVehicle(organizationId, "Truck-WU5-Crossing");
        UUID enterGeofenceId = insertSquareGeofence(organizationId, "Depot Entry", CENTER_LON, GEOFENCE_LAT, HALF_WIDTH_DEG, "on_enter", null);
        UUID exitGeofenceId = insertSquareGeofence(organizationId, "Depot Exit", CENTER_LON, GEOFENCE_LAT, HALF_WIDTH_DEG, "on_exit", null);

        connectDevicePublisher();
        warmUpUntilSubscribed();
        List<String> receivedAlerts = subscribeToAlerts(organizationId);

        double[] lonOffsets = {-0.02, -0.01, -0.006, -0.003, 0.0, 0.003, 0.006, 0.01, 0.02};
        publishTraceSequentially(vehicleId, GEOFENCE_LAT, lonOffsets);

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
            assertThat(countAlerts(vehicleId, enterGeofenceId, "enter")).isEqualTo(1));
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
            assertThat(countAlerts(vehicleId, exitGeofenceId, "exit")).isEqualTo(1));
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(receivedAlerts).hasSize(2));
        Thread.sleep(500);

        assertThat(countAlerts(vehicleId, enterGeofenceId, "exit")).isZero();
        assertThat(countAlerts(vehicleId, exitGeofenceId, "enter")).isZero();
        assertThat(countAlerts(vehicleId)).isEqualTo(2);
        assertThat(receivedAlerts).hasSize(2);
        assertThat(readIsInside(vehicleId, enterGeofenceId)).isFalse();
        assertThat(readIsInside(vehicleId, exitGeofenceId)).isFalse();
    }

    // Test 6.6, richer than WU4's own single-message guard proof: a full
    // backlog of positions that crossed the depot boundary while the
    // vehicle was offline, all older than a "live" position already known
    // (Geo.isImplausible never rejects an older resend regardless of
    // distance -- Geo.speedKmh returns empty when next is not after prev --
    // so the live position is placed far away deliberately, to prove
    // clean isolation: it must never itself register as a geofence
    // membership change). THEN clause: zero alerts of either type, AND the
    // positions still land in the history table.
    @Test
    void resentBacklogOfOlderTelemetryAcrossAGeofenceCrossingNeverGeneratesRetroactiveAlerts() throws Exception {
        migrate();
        context = startContext(1, Duration.ofMillis(100), 1, Duration.ZERO);
        UUID organizationId = insertOrganization("Acme Backlog Org");
        UUID vehicleId = insertVehicle(organizationId, "Truck-WU5-Backlog");
        UUID enterGeofenceId = insertSquareGeofence(organizationId, "Depot Entry", CENTER_LON, GEOFENCE_LAT, HALF_WIDTH_DEG, "on_enter", null);
        UUID exitGeofenceId = insertSquareGeofence(organizationId, "Depot Exit", CENTER_LON, GEOFENCE_LAT, HALF_WIDTH_DEG, "on_exit", null);

        connectDevicePublisher();
        warmUpUntilSubscribed();

        Instant live = Instant.now();
        publishTelemetry(vehicleId, telemetryPayload(live, DECOY_LAT, DECOY_LON));
        await().atMost(Duration.ofSeconds(20)).until(() -> countPositions(vehicleId) >= 1);

        // Backlog, oldest first: outside -> inside -> inside -> inside ->
        // outside, a full crossing that "happened" while disconnected --
        // every one of these predates `live` above.
        double[] backlogOffsets = {-0.01, -0.003, 0.0, 0.003, 0.01};
        long[] secondsBeforeLive = {500, 450, 400, 350, 300};
        for (int i = 0; i < backlogOffsets.length; i++) {
            Instant recordedAt = live.minusSeconds(secondsBeforeLive[i]);
            publishTelemetry(vehicleId, telemetryPayload(recordedAt, GEOFENCE_LAT, CENTER_LON + backlogOffsets[i]));
        }
        int expectedPositions = 1 + backlogOffsets.length;
        await().atMost(Duration.ofSeconds(20)).until(() -> countPositions(vehicleId) >= expectedPositions);
        // Give any wrongly-eligible dispatch a moment to land before asserting its absence.
        Thread.sleep(500);

        assertThat(countPositions(vehicleId)).isEqualTo(expectedPositions);
        assertThat(countAlerts(vehicleId)).isZero();
        assertThat(readIsInside(vehicleId, enterGeofenceId)).isNull();
        assertThat(readIsInside(vehicleId, exitGeofenceId)).isNull();
    }

    // Test 6.7: dwellSecs elapses (measured from `since`, the confirmed-
    // inside timestamp, to the current reading's recordedAt -- all
    // application-level timestamps, no real sleep needed) exactly once
    // while the vehicle stays inside without ever exiting.
    @Test
    void onDwellFiresExactlyOnceWhileTheVehicleRemainsInsidePastTheThreshold() throws Exception {
        migrate();
        context = startContext(1, Duration.ofMillis(100), 1, Duration.ZERO);
        UUID organizationId = insertOrganization("Acme Dwell Org");
        UUID vehicleId = insertVehicle(organizationId, "Truck-WU5-Dwell");
        UUID geofenceId = insertSquareGeofence(organizationId, "Dwell Depot", CENTER_LON, GEOFENCE_LAT, HALF_WIDTH_DEG, "on_dwell", 60);

        connectDevicePublisher();
        warmUpUntilSubscribed();

        Instant t0 = Instant.now();
        publishTelemetry(vehicleId, telemetryPayload(t0, INSIDE_LAT, INSIDE_LON));
        await().atMost(Duration.ofSeconds(20)).until(() -> countPositions(vehicleId) >= 1);
        assertThat(countAlerts(vehicleId, geofenceId, "dwell")).isZero();

        publishTelemetry(vehicleId, telemetryPayload(t0.plusSeconds(70), INSIDE_LAT, INSIDE_LON));
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
            assertThat(countAlerts(vehicleId, geofenceId, "dwell")).isEqualTo(1));

        publishTelemetry(vehicleId, telemetryPayload(t0.plusSeconds(140), INSIDE_LAT, INSIDE_LON));
        await().atMost(Duration.ofSeconds(20)).until(() -> countPositions(vehicleId) >= 3);
        Thread.sleep(500);

        assertThat(countAlerts(vehicleId, geofenceId, "dwell")).isEqualTo(1);
    }

    // Test 6.8: several vehicles already confirmed inside, the process
    // restarts (a brand new Spring context with its own fresh JdbcTemplate
    // and MQTT client identities -- nothing shared with the closed one, the
    // same "no in-memory state to lose" property TelemetryImplausibilityFilter's
    // OWN in-memory map does NOT have, which is exactly what makes this test
    // meaningful: geofence membership is not tracked there, only in
    // vehicle_fence_state), and receiving further still-inside telemetry
    // must not re-fire entry alerts for vehicles that already constaban
    // dentro.
    @Test
    void restartingTheProcessorPreservesMembershipStateForVehiclesAlreadyInside() throws Exception {
        migrate();
        context = startContext(1, Duration.ofMillis(100), 1, Duration.ZERO);
        UUID organizationId = insertOrganization("Acme Restart Org");
        UUID vehicle1 = insertVehicle(organizationId, "Truck-WU5-Restart-1");
        UUID vehicle2 = insertVehicle(organizationId, "Truck-WU5-Restart-2");
        UUID geofenceId = insertSquareGeofence(organizationId, "Depot", CENTER_LON, GEOFENCE_LAT, HALF_WIDTH_DEG, "on_enter", null);

        connectDevicePublisher();
        warmUpUntilSubscribed();

        Instant t0 = Instant.now();
        publishTelemetry(vehicle1, telemetryPayload(t0, INSIDE_LAT, INSIDE_LON));
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
            assertThat(countAlerts(vehicle1, geofenceId, "enter")).isEqualTo(1));
        publishTelemetry(vehicle2, telemetryPayload(t0, INSIDE_LAT, INSIDE_LON));
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
            assertThat(countAlerts(vehicle2, geofenceId, "enter")).isEqualTo(1));
        assertThat(readIsInside(vehicle1, geofenceId)).isTrue();
        assertThat(readIsInside(vehicle2, geofenceId)).isTrue();

        // "Restart": close this context entirely -- its JdbcTemplate,
        // TelemetryImplausibilityFilter's in-memory map, and its MQTT
        // listener identity are all discarded -- then bring up a
        // completely fresh one against the SAME two containers.
        context.close();
        context = startContext(1, Duration.ofMillis(100), 1, Duration.ZERO);
        warmUpUntilSubscribed();

        publishTelemetry(vehicle1, telemetryPayload(t0.plusSeconds(70), INSIDE_LAT, INSIDE_LON));
        await().atMost(Duration.ofSeconds(20)).until(() -> countPositions(vehicle1) >= 2);
        publishTelemetry(vehicle2, telemetryPayload(t0.plusSeconds(70), INSIDE_LAT, INSIDE_LON));
        await().atMost(Duration.ofSeconds(20)).until(() -> countPositions(vehicle2) >= 2);
        Thread.sleep(500);

        assertThat(countAlerts(vehicle1, geofenceId, "enter")).isEqualTo(1);
        assertThat(countAlerts(vehicle2, geofenceId, "enter")).isEqualTo(1);
        assertThat(readIsInside(vehicle1, geofenceId)).isTrue();
        assertThat(readIsInside(vehicle2, geofenceId)).isTrue();
    }

    // Publishes one point per offset in `lonOffsets` (all at `lat`), spacing
    // each recordedAt far enough ahead of the previous one that the implied
    // speed (Geo/TelemetryImplausibilityFilter, maxSpeedKmh=300 below) never
    // rejects the jump -- computed from the real distance between
    // consecutive points, not a fixed guess, so this stays correct
    // regardless of exactly how the offsets are chosen. Awaits each point's
    // full writeBatch (including geofence dispatch) before publishing the
    // next, the same ordering technique GeofenceAlertEndToEndTest (WU4)
    // established.
    private void publishTraceSequentially(UUID vehicleId, double lat, double[] lonOffsets) throws Exception {
        Instant cursor = Instant.now();
        Double prevLat = null;
        Double prevLon = null;
        int expected = countPositions(vehicleId);
        for (double lonOffset : lonOffsets) {
            double lon = CENTER_LON + lonOffset;
            if (prevLat != null) {
                double distanceMeters = Geo.distanceMeters(
                    new GeoPoint(prevLat, prevLon, Instant.EPOCH), new GeoPoint(lat, lon, Instant.EPOCH)
                );
                cursor = cursor.plusSeconds(Math.max(2, (long) Math.ceil(distanceMeters / 40.0)));
            }
            publishTelemetry(vehicleId, telemetryPayload(cursor, lat, lon));
            expected++;
            int expectedCount = expected;
            await().atMost(Duration.ofSeconds(20)).until(() -> countPositions(vehicleId) >= expectedCount);
            prevLat = lat;
            prevLon = lon;
        }
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
            () -> new FleetpulseGeofencingProperties(confirmationReadings, confirmationDuration, 15.0, Duration.ofMinutes(10)));
        // Task 2.4 (06-add-trips-eta-alerts, WU2): no destinations are ever
        // assigned by this test -- see GeofenceAlertEndToEndTest's identical
        // registration for the full reasoning.
        ctx.registerBean(FleetpulseEtaProperties.class, () -> new FleetpulseEtaProperties(1.3, 0.3, 5.0, 30.0, Duration.ofMinutes(15)));
        ctx.registerBean(EtaPublisher.class, () -> (organizationId, vehicleId, estimate, calculatedAt) -> { });
        // Task 3.2/WU3: this test proves the geofencing spec scenarios, not
        // speeding/excessive-idle alerting -- same "no-op publisher, real
        // writer/silence-state store" reasoning as GeofenceAlertEndToEndTest.
        ctx.registerBean(FleetpulseAlertingProperties.class,
            () -> new FleetpulseAlertingProperties(100.0, Duration.ofMinutes(10), Duration.ofMinutes(15)));
        ctx.registerBean(AlertPublisher.class, () -> alert -> { });
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
        java.util.concurrent.CopyOnWriteArrayList<String> received = new java.util.concurrent.CopyOnWriteArrayList<>();
        alertSubscriber.subscribe("fleet/" + organizationId + "/alerts", 2, (topic, message) ->
            received.add(new String(message.getPayload(), StandardCharsets.UTF_8)));
        return received;
    }

    // Same technique GeofenceAlertEndToEndTest (WU4) established: publish a
    // throwaway decoy message on a NEVER-asserted-against vehicle until the
    // currently-running context's inbound subscription is confirmed active,
    // then discard it.
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
    // task 3.1) -- see GeofenceAlertEndToEndTest's identical helper for the
    // full reasoning.
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

    // DoD "exactamente dos alertas, ni una mas": the total across every
    // geofence and every alert type for this vehicle, not just the two
    // typed counts above -- a bug that fired a spurious third alert (of
    // either type, on either geofence) would still be caught here even if
    // it happened to land on a geofence/type combination no other
    // assertion in this test checks individually. Deliberately NOT filtered
    // to geofence_% alert_type values: this test never produces a
    // speeding/excessive_idle condition either (no-op AlertPublisher, see
    // startContext's own comment), so an unfiltered count over the unified
    // `alerts` table proves the same thing a geofence_alerts-only count did
    // before this table existed.
    private static int countAlerts(UUID vehicleId) throws SQLException {
        try (
            Connection connection = connect();
            PreparedStatement statement = connection.prepareStatement("SELECT count(*) FROM alerts WHERE vehicle_id = ?")
        ) {
            statement.setObject(1, vehicleId);
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
}

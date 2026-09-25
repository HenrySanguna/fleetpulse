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

// T8 (prod-qa-findings, "geofence alert silence window"): the dedicated
// end-to-end proof that GeofenceRuleDispatcher's new silence layer actually
// stops the alert-inbox flood, through the real dual-container (PostGIS +
// Mosquitto) pipeline GeofenceEndToEndScenarioTest/GeofenceOscillationEndToEndTest
// already established. Kept in its own file, same "one concern per
// dual-container file" convention: GeofenceOscillationEndToEndTest proves the
// EXISTING hysteresis layer (confirmation/exit buffering) damps GPS jitter
// into a stable membership transition in the first place; this file proves
// the SEPARATE, second layer added on top of it -- once genuine, CONFIRMED
// transitions still repeat (a vehicle truly parking right at a boundary),
// only the first of a burst within the window is notified, and the window
// re-arms afterwards. confirmationReadings=1/confirmationDuration=ZERO below
// (the same "instant confirmation" profile GeofenceEndToEndScenarioTest's own
// 6.1 test uses) deliberately removes the hysteresis layer's OWN damping from
// this test, so every alternating inside/outside reading is a genuinely
// confirmed transition and the silencing this test asserts is never
// incidentally helped along by confirmation delay.
@Testcontainers
class GeofenceAlertSilenceEndToEndTest {

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
    private static final double INSIDE_LON = CENTER_LON;
    // Well outside both the square and its (unused here, confirmationReadings=1
    // makes the buffer irrelevant to entering) exit buffer -- same 0.02 deg
    // scale GeofenceEndToEndScenarioTest's own crossing test uses.
    private static final double OUTSIDE_LON = CENTER_LON - 0.02;

    // T8's own production default (FleetpulseGeofencingProperties.silenceWindow).
    private static final Duration SILENCE_WINDOW = Duration.ofMinutes(10);

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

    // One continuous timeline, all timestamps application-level (each
    // message's own recordedAt), not real sleep -- the same technique every
    // dual-container test in this package already uses to simulate minutes
    // of elapsed time in seconds of real test run time. Two geofences over
    // the SAME square (one on_enter, one on_exit, GeofenceEndToEndScenarioTest's
    // own 6.1 setup) so both GEOFENCE_ENTER and GEOFENCE_EXIT silencing are
    // proven, on independent (vehicle, geofence, type) keys.
    @Test
    void oscillationWithinTheWindowIsSilencedAndReEntryAfterItElapsesFiresAgain() throws Exception {
        migrate();
        context = startContext();
        UUID organizationId = insertOrganization("Acme Silence Org");
        UUID vehicleId = insertVehicle(organizationId, "Truck-T8-Silence");
        UUID enterGeofenceId = insertSquareGeofence(organizationId, "Silence Depot Enter", CENTER_LON, GEOFENCE_LAT, HALF_WIDTH_DEG, "on_enter", null);
        UUID exitGeofenceId = insertSquareGeofence(organizationId, "Silence Depot Exit", CENTER_LON, GEOFENCE_LAT, HALF_WIDTH_DEG, "on_exit", null);

        connectDevicePublisher();
        warmUpUntilSubscribed(1);
        List<String> receivedAlerts = subscribeToAlerts(organizationId);

        Instant t0 = Instant.now();

        // t0: ENTER #1 -- the very first alert of this key, always fires.
        publishAndAwaitPosition(vehicleId, t0, GEOFENCE_LAT, INSIDE_LON, 1);
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
            assertThat(countAlerts(vehicleId, enterGeofenceId, "enter")).isEqualTo(1));

        // t0+60s: EXIT #1 -- the first alert of THIS OTHER key, always fires.
        // (60s, not less: 2.2km/60s keeps the implied speed comfortably
        // under FleetpulseTelemetryImplausibilityProperties' 300 km/h cap.)
        publishAndAwaitPosition(vehicleId, t0.plusSeconds(60), GEOFENCE_LAT, OUTSIDE_LON, 2);
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
            assertThat(countAlerts(vehicleId, exitGeofenceId, "exit")).isEqualTo(1));

        // t0+3min and t0+6min: the vehicle oscillates back in and out, well
        // within SILENCE_WINDOW of each key's own last alert -- both
        // confirmed transitions (this is instant-confirmation, so both
        // really do flip membership and reach GeofenceRuleEngine's
        // firedAlerts()) but neither is notified again. Awaited via each
        // key's OWN alert_silence_state row (written once GeofenceRuleDispatcher
        // finishes the message that touched it -- see that class's own
        // comment) instead of a blind sleep: its updated_at moves forward
        // even when the alert itself is suppressed, so seeing it move is a
        // positive signal that this message's dispatch, including any
        // wrongly-unsuppressed alert write, has already happened.
        Instant enterSilenceUpdatedBeforeOscillation = silenceStateUpdatedAt(vehicleId, enterGeofenceId, "enter");
        publishAndAwaitPosition(vehicleId, t0.plus(Duration.ofMinutes(3)), GEOFENCE_LAT, INSIDE_LON, 3);
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
            assertThat(silenceStateUpdatedAt(vehicleId, enterGeofenceId, "enter")).isAfter(enterSilenceUpdatedBeforeOscillation));

        Instant exitSilenceUpdatedBeforeOscillation = silenceStateUpdatedAt(vehicleId, exitGeofenceId, "exit");
        publishAndAwaitPosition(vehicleId, t0.plus(Duration.ofMinutes(6)), GEOFENCE_LAT, OUTSIDE_LON, 4);
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
            assertThat(silenceStateUpdatedAt(vehicleId, exitGeofenceId, "exit")).isAfter(exitSilenceUpdatedBeforeOscillation));

        assertThat(countAlerts(vehicleId, enterGeofenceId, "enter")).isEqualTo(1);
        assertThat(countAlerts(vehicleId, exitGeofenceId, "exit")).isEqualTo(1);
        assertThat(countAlerts(vehicleId)).isEqualTo(2);
        assertThat(receivedAlerts).hasSize(2);

        // t0+11min: past ENTER's own SILENCE_WINDOW (from t0) -- fires again.
        publishAndAwaitPosition(vehicleId, t0.plus(Duration.ofMinutes(11)), GEOFENCE_LAT, INSIDE_LON, 5);
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
            assertThat(countAlerts(vehicleId, enterGeofenceId, "enter")).isEqualTo(2));

        // t0+16min: past EXIT's own SILENCE_WINDOW (from t0+60s) -- fires again.
        publishAndAwaitPosition(vehicleId, t0.plus(Duration.ofMinutes(16)), GEOFENCE_LAT, OUTSIDE_LON, 6);
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
            assertThat(countAlerts(vehicleId, exitGeofenceId, "exit")).isEqualTo(2));
        // receivedAlerts is filled by the MQTT subscriber's own async
        // callback, not by the DB write the assertion above already
        // awaited -- await its own positive signal too, instead of a blind
        // sleep, before asserting the exact final counts below.
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(receivedAlerts).hasSize(4));

        assertThat(countAlerts(vehicleId, enterGeofenceId, "enter")).isEqualTo(2);
        assertThat(countAlerts(vehicleId, exitGeofenceId, "exit")).isEqualTo(2);
        assertThat(countAlerts(vehicleId)).isEqualTo(4);
    }

    // T8 review finding: several fired alerts for the SAME key can fall
    // inside ONE evaluateAndDispatch batch, not just across separate
    // flushes like the test above -- this class's own class-level comment
    // documents that the in-memory silenceStates map, not a DB re-read, is
    // what chains those occurrences within a single call. maxSize=3 (a
    // long flushInterval so its own periodic trigger never fires first)
    // forces all three positions below into the SAME
    // TelemetryPositionBuffer flush, hence the SAME writeBatch call, hence
    // the SAME GeofenceRuleDispatcher.evaluateAndDispatch call.
    @Test
    void severalFiredAlertsForTheSameKeyWithinOneBatchOnlyEmitTheFirst() throws Exception {
        migrate();
        context = startContext(3, Duration.ofSeconds(30));
        UUID organizationId = insertOrganization("Acme Silence Batch Org");
        UUID vehicleId = insertVehicle(organizationId, "Truck-T8-Silence-Batch");
        UUID enterGeofenceId =
            insertSquareGeofence(organizationId, "Silence Batch Depot Enter", CENTER_LON, GEOFENCE_LAT, HALF_WIDTH_DEG, "on_enter", null);

        connectDevicePublisher();
        warmUpUntilSubscribed(3);
        List<String> receivedAlerts = subscribeToAlerts(organizationId);

        Instant t0 = Instant.now();
        // ENTER, EXIT (on_enter never alerts on exit, so this one is silent),
        // ENTER again -- published back to back with no await in between,
        // so all three land in the buffer before its maxSize=3 triggers the
        // one flush. Both ENTER occurrences are genuinely confirmed
        // transitions (instant confirmation, same profile as the test
        // above) and well within SILENCE_WINDOW of each other.
        publishTelemetry(vehicleId, telemetryPayload(t0, GEOFENCE_LAT, INSIDE_LON));
        publishTelemetry(vehicleId, telemetryPayload(t0.plusSeconds(60), GEOFENCE_LAT, OUTSIDE_LON));
        publishTelemetry(vehicleId, telemetryPayload(t0.plusSeconds(120), GEOFENCE_LAT, INSIDE_LON));

        await().atMost(Duration.ofSeconds(20)).until(() -> countPositions(vehicleId) >= 3);
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
            assertThat(countAlerts(vehicleId, enterGeofenceId, "enter")).isEqualTo(1));
        // receivedAlerts is filled by the MQTT subscriber's own async
        // callback, a separate signal from the DB write already awaited
        // above -- await it too before asserting the final exact counts.
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(receivedAlerts).hasSize(1));

        assertThat(countAlerts(vehicleId)).isEqualTo(1);
    }

    private void publishAndAwaitPosition(UUID vehicleId, Instant recordedAt, double lat, double lon, int expectedPositionCount) throws Exception {
        publishTelemetry(vehicleId, telemetryPayload(recordedAt, lat, lon));
        await().atMost(Duration.ofSeconds(20)).until(() -> countPositions(vehicleId) >= expectedPositionCount);
    }

    private AnnotationConfigApplicationContext startContext() {
        return startContext(1, Duration.ofMillis(100));
    }

    // bufferMaxSize/bufferFlushInterval parameterized so
    // severalFiredAlertsForTheSameKeyWithinOneBatchOnlyEmitTheFirst() can
    // force several messages into the SAME TelemetryPositionBuffer flush
    // (and therefore the same GeofenceRuleDispatcher.evaluateAndDispatch
    // call) instead of this class's own default of one message per flush.
    private AnnotationConfigApplicationContext startContext(int bufferMaxSize, Duration bufferFlushInterval) {
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        ctx.registerBean(FleetpulseMqttProperties.class, () -> new FleetpulseMqttProperties(brokerUrl()));
        ctx.registerBean(FleetpulseMqttServiceCredentialsProperties.class, () -> new FleetpulseMqttServiceCredentialsProperties(
            SecuredMosquittoTestSupport.SERVICE_USERNAME, SecuredMosquittoTestSupport.SERVICE_PASSWORD
        ));
        ctx.registerBean(MeterRegistry.class, SimpleMeterRegistry::new);
        ctx.registerBean(FleetpulseTelemetryImplausibilityProperties.class, () -> new FleetpulseTelemetryImplausibilityProperties(300.0));
        ctx.registerBean(FleetpulseTelemetryBufferProperties.class,
            () -> new FleetpulseTelemetryBufferProperties(bufferMaxSize, bufferFlushInterval));
        ctx.registerBean(FleetpulseMotionDetectionProperties.class, () -> new FleetpulseMotionDetectionProperties(5.0, 12.0, Duration.ofSeconds(30)));
        // confirmationReadings=1/confirmationDuration=ZERO: see this class's
        // own comment for why instant confirmation isolates the silence
        // layer under test from the unrelated hysteresis layer.
        ctx.registerBean(FleetpulseGeofencingProperties.class,
            () -> new FleetpulseGeofencingProperties(1, Duration.ZERO, 15.0, SILENCE_WINDOW));
        ctx.registerBean(FleetpulseEtaProperties.class, () -> new FleetpulseEtaProperties(1.3, 0.3, 5.0, 30.0, Duration.ofMinutes(15)));
        ctx.registerBean(EtaPublisher.class, () -> (organizationId, vehicleId, estimate, calculatedAt) -> { });
        // This test proves geofence alert silencing, not speeding/excessive-idle
        // -- same no-op AlertPublisher, real writer/silence-state store
        // reasoning as GeofenceOscillationEndToEndTest.
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
        CopyOnWriteArrayList<String> received = new CopyOnWriteArrayList<>();
        alertSubscriber.subscribe("fleet/" + organizationId + "/alerts", 2, (topic, message) ->
            received.add(new String(message.getPayload(), StandardCharsets.UTF_8)));
        return received;
    }

    // Same technique GeofenceAlertEndToEndTest/GeofenceEndToEndScenarioTest
    // established: publish a throwaway decoy on a never-asserted-against
    // vehicle until the subscription is confirmed active, then discard it.
    // bufferMaxSize decoys per attempt, not one, so this also warms up a
    // context started with a buffer maxSize > 1 (severalFiredAlertsFor...
    // below): with a single decoy, a bigger buffer would only ever flush on
    // its own flushInterval, not on this method's own short per-attempt
    // await.
    private void warmUpUntilSubscribed(int bufferMaxSize) throws Exception {
        UUID warmupOrganizationId = insertOrganization("Warmup Org " + UUID.randomUUID());
        UUID warmupVehicleId = insertVehicle(warmupOrganizationId, "Warmup Vehicle");
        Instant warmupRecordedAt = Instant.now().minusSeconds(7200);
        for (int attempt = 1; attempt <= 10; attempt++) {
            for (int i = 0; i < bufferMaxSize; i++) {
                publishTelemetry(warmupVehicleId, telemetryPayload(warmupRecordedAt, DECOY_LAT, DECOY_LON));
            }
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

    // Positive signal for "this message's own GeofenceRuleDispatcher
    // dispatch, including any wrongly-unsuppressed alert write, has already
    // happened" -- see GeofenceRuleDispatcher's own class comment:
    // writeBulk() for a message's mutated keys runs only after that
    // message's alerts/fence-state are already written and published, so
    // this row's updated_at moving forward proves the whole message is
    // done, not just that its position landed.
    private static Instant silenceStateUpdatedAt(UUID vehicleId, UUID geofenceId, String alertType) throws SQLException {
        try (
            Connection connection = connect();
            PreparedStatement statement = connection.prepareStatement(
                "SELECT updated_at FROM alert_silence_state WHERE vehicle_id = ? AND alert_type = ? AND context = ?"
            )
        ) {
            statement.setObject(1, vehicleId);
            statement.setString(2, "geofence_" + alertType);
            statement.setString(3, geofenceId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                Timestamp updatedAt = resultSet.getTimestamp("updated_at");
                return updatedAt == null ? null : updatedAt.toInstant();
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

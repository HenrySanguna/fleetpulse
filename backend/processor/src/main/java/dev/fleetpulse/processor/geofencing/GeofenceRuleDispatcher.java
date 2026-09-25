package dev.fleetpulse.processor.geofencing;

import dev.fleetpulse.geocore.FenceMembershipConfig;
import dev.fleetpulse.geocore.FenceMembershipSample;
import dev.fleetpulse.geocore.FenceMembershipState;
import dev.fleetpulse.processor.alerts.AlertSilenceDecision;
import dev.fleetpulse.processor.alerts.AlertSilenceEngine;
import dev.fleetpulse.processor.alerts.AlertSilenceKey;
import dev.fleetpulse.processor.alerts.AlertSilenceState;
import dev.fleetpulse.processor.alerts.AlertType;
import dev.fleetpulse.processor.alerts.JdbcAlertSilenceStateStore;
import dev.fleetpulse.processor.config.FleetpulseGeofencingProperties;
import dev.fleetpulse.processor.telemetry.TelemetryMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Array;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

// Task 2.4's architecture decision (tasks.md forecast): JdbcTelemetryPositionWriter
// only ever calls evaluateAndDispatch() with the subset of a flush batch it
// has already determined is newer than the last evaluated message for that
// vehicle -- see JdbcTelemetryPositionWriter's own class comment for exactly
// how that subset is computed and why. This class does not re-derive
// eligibility itself, the same way VehicleMotionStreakTracker does not
// duplicate the guard's own comparison either.
//
// Tasks 4.1-4.3: per eligible message, per relevant geofence --
// GeofenceEvaluator (WU2, extended WU4) supplies the spatial/metadata reads,
// GeofenceRuleEngine (pure, WU4) decides the confirmed membership state and
// which alerts fire, and this class owns the two writes
// (JdbcVehicleFenceStateWriter, JdbcGeofenceAlertWriter) plus the MQTT
// publish (GeofenceAlertPublisher).
//
// Deliberately one synchronous read-then-write round trip per
// (message, geofence) pair, not a batched compute-then-flush like
// VehicleMotionStreakTracker's own `running` map: a flush batch containing
// two messages for the SAME vehicle needs its second message's evaluation to
// see the first message's already-persisted vehicle_fence_state row, and a
// vehicle can be tracked against multiple geofences at once -- an in-memory
// running map keyed by (vehicleId, geofenceId) would be materially more
// complex than motion's single per-vehicle value for a case that, in
// practice, is rare (multiple geofence-relevant messages for one vehicle in
// one flush). A synchronous write on the same JdbcTemplate/connection gives
// correct chaining for free through ordinary read-after-write, without
// needing WU3-style Java-side chaining here too.
//
// T8 (prod-qa-findings, "geofence alert silence window"): a SECOND,
// independent layer sits on top of the hysteresis above -- confirmation/exit
// buffering already damps GPS-boundary jitter into a stable membership
// transition, but a vehicle can still genuinely oscillate (cross the
// buffered boundary repeatedly over minutes, e.g. parking right at a depot
// gate) and produce a real ENTER/EXIT/ENTER/EXIT sequence of firedAlerts(),
// each one a legitimate confirmed transition on its own. AlertSilenceEngine
// (alerts package) already solves this exact shape for a CONTINUOUS
// condition (speeding/excessive-idle: conditionActive reflects whether the
// condition holds on THIS message, and the engine resets to inactive when it
// does not). A geofence alert has no such continuous condition to sample --
// GeofenceRuleEngine only ever adds a type to firedAlerts() at the instant a
// transition/dwell is confirmed -- so this class calls
// AlertSilenceEngine.evaluate() ONLY at that instant, always with
// conditionActive=true, never with false. Concretely: the first alert of a
// (vehicle, geofence, type) always fires (freshEpisode, prev.active() was
// false); once fired, the persisted state's active flag stays true forever
// for that key (this class never calls evaluate() with conditionActive=false
// to reset it), so every later occurrence falls through to the engine's
// windowElapsed check alone -- exactly "suppress a repeat within the window,
// re-arm once it elapses," with no notion of "episode" needed for an event.
// Persisted the same bulk (load once, chain in Java, write once) shape
// AlertRuleDispatcher uses, NOT this class's own per-(message, geofence)
// membership-state round trip above: unlike that read, which needs each
// message's own within-batch chaining to see the very last dwellAlerted it
// wrote, this layer's state per key never needs to be re-read mid-batch from
// the DB (the in-memory map IS the source of truth for the duration of one
// evaluateAndDispatch call), so a single upfront load and a single trailing
// write are both correct and reuse JdbcAlertSilenceStateStore as-is.
@Component
public class GeofenceRuleDispatcher {

    private static final Logger log = LoggerFactory.getLogger(GeofenceRuleDispatcher.class);

    private static final String VEHICLE_ORGANIZATION_IDS_SQL = "SELECT id, organization_id FROM vehicles WHERE id = ANY (?)";
    private static final List<AlertType> GEOFENCE_ALERT_TYPES =
        List.of(AlertType.GEOFENCE_ENTER, AlertType.GEOFENCE_EXIT, AlertType.GEOFENCE_DWELL);

    private final JdbcTemplate jdbcTemplate;
    private final GeofenceEvaluator geofenceEvaluator;
    private final FleetpulseGeofencingProperties geofencingProperties;
    private final GeofenceAlertPublisher alertPublisher;
    private final JdbcVehicleFenceStateWriter fenceStateWriter;
    private final JdbcGeofenceAlertWriter alertWriter;
    private final JdbcAlertSilenceStateStore silenceStateStore;

    public GeofenceRuleDispatcher(
        JdbcTemplate jdbcTemplate,
        GeofenceEvaluator geofenceEvaluator,
        FleetpulseGeofencingProperties geofencingProperties,
        GeofenceAlertPublisher alertPublisher,
        JdbcVehicleFenceStateWriter fenceStateWriter,
        JdbcGeofenceAlertWriter alertWriter,
        JdbcAlertSilenceStateStore silenceStateStore
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.geofenceEvaluator = geofenceEvaluator;
        this.geofencingProperties = geofencingProperties;
        this.alertPublisher = alertPublisher;
        this.fenceStateWriter = fenceStateWriter;
        this.alertWriter = alertWriter;
        this.silenceStateStore = silenceStateStore;
    }

    public void evaluateAndDispatch(List<TelemetryMessage> eligibleMessages) {
        if (eligibleMessages.isEmpty()) {
            return;
        }
        Map<UUID, UUID> organizationIdByVehicle = loadOrganizationIds(eligibleMessages);
        FenceMembershipConfig config = new FenceMembershipConfig(
            geofencingProperties.confirmationReadings(), geofencingProperties.confirmationDuration()
        );
        Map<AlertSilenceKey, AlertSilenceState> silenceStates =
            new HashMap<>(silenceStateStore.loadBulkByVehicleAndType(organizationIdByVehicle.keySet(), GEOFENCE_ALERT_TYPES));
        for (TelemetryMessage message : eligibleMessages) {
            UUID organizationId = organizationIdByVehicle.get(message.vehicleId());
            if (organizationId == null) {
                // Defensive only: every row in `vehicles` has a NOT NULL
                // organization_id FK, so this means the vehicle itself no
                // longer exists (a resend racing a hard delete) -- nothing
                // to evaluate it against.
                continue;
            }
            processMessage(organizationId, message, config, silenceStates);
        }
        silenceStateStore.writeBulk(silenceStates);
    }

    private void processMessage(
        UUID organizationId, TelemetryMessage message, FenceMembershipConfig config,
        Map<AlertSilenceKey, AlertSilenceState> silenceStates
    ) {
        Set<UUID> insideStrict = geofenceEvaluator.containingGeofenceIds(organizationId, message.lat(), message.lon());
        Map<UUID, GeofenceMembershipRecord> previousStates = geofenceEvaluator.loadActiveMembershipStates(message.vehicleId());

        Set<UUID> relevantGeofenceIds = new HashSet<>(insideStrict);
        relevantGeofenceIds.addAll(previousStates.keySet());
        if (relevantGeofenceIds.isEmpty()) {
            return;
        }

        Set<UUID> needsBufferCheck = new HashSet<>(relevantGeofenceIds);
        needsBufferCheck.removeAll(insideStrict);
        Set<UUID> insideBufferedFromQuery = geofenceEvaluator
            .bufferedContainmentAmong(needsBufferCheck, message.lat(), message.lon(), geofencingProperties.exitBufferMeters());

        Map<UUID, GeofenceRule> rules = geofenceEvaluator.loadRules(relevantGeofenceIds);

        for (UUID geofenceId : relevantGeofenceIds) {
            GeofenceRule rule = rules.get(geofenceId);
            if (rule == null) {
                // Geofence hard-deleted since being referenced; nothing left to evaluate it against.
                continue;
            }
            dispatchForGeofence(
                organizationId, message, config, geofenceId, rule, insideStrict, insideBufferedFromQuery, previousStates, silenceStates
            );
        }
    }

    private void dispatchForGeofence(
        UUID organizationId,
        TelemetryMessage message,
        FenceMembershipConfig config,
        UUID geofenceId,
        GeofenceRule rule,
        Set<UUID> insideStrict,
        Set<UUID> insideBufferedFromQuery,
        Map<UUID, GeofenceMembershipRecord> previousStates,
        Map<AlertSilenceKey, AlertSilenceState> silenceStates
    ) {
        GeofenceMembershipRecord previous = previousStates.get(geofenceId);
        FenceMembershipState prevState = previous != null
            ? previous.state()
            : FenceMembershipState.confirmed(false, message.recordedAt());
        boolean dwellAlerted = previous != null && previous.dwellAlerted();

        boolean strict = insideStrict.contains(geofenceId);
        boolean buffered = strict || insideBufferedFromQuery.contains(geofenceId);
        FenceMembershipSample sample = new FenceMembershipSample(strict, buffered, message.recordedAt());

        GeofenceRuleOutcome outcome = GeofenceRuleEngine.next(prevState, dwellAlerted, rule, sample, config);

        fenceStateWriter.write(message.vehicleId(), geofenceId, outcome.nextState(), outcome.dwellAlerted());

        for (GeofenceAlertType alertType : outcome.firedAlerts()) {
            AlertSilenceKey silenceKey = new AlertSilenceKey(message.vehicleId(), toSilenceAlertType(alertType), geofenceId);
            AlertSilenceState previousSilenceState = silenceStates.getOrDefault(silenceKey, AlertSilenceState.NONE);
            // conditionActive is always true here: see this class's own
            // comment for why an event (not a continuous condition) only
            // ever calls the engine at the instant it fires.
            AlertSilenceDecision decision =
                AlertSilenceEngine.evaluate(previousSilenceState, true, message.recordedAt(), geofencingProperties.silenceWindow());
            silenceStates.put(silenceKey, decision.nextState());
            if (!decision.shouldFire()) {
                continue;
            }

            GeofenceAlert alert = new GeofenceAlert(
                UUID.randomUUID(), organizationId, message.vehicleId(), geofenceId, alertType, message.recordedAt()
            );
            // Persisted first (the durable history/console record) before
            // the best-effort MQTT publish below: a temporarily unreachable
            // broker must never cost an entry in the alert history, and
            // must never break telemetry ingestion for positions/vehicle_state
            // either (see the catch below).
            alertWriter.write(alert);
            try {
                alertPublisher.publish(alert);
            } catch (RuntimeException ex) {
                log.warn("Failed to publish geofence alert {} for vehicle {} geofence {}",
                    alertType, message.vehicleId(), geofenceId, ex);
            }
        }
    }

    // The silence layer keys by the SAME unified AlertType the `alerts`
    // table/JdbcAlertSilenceStateStore already use (AlertType.wireValue(),
    // V12) rather than GeofenceAlertType's own unprefixed wire value --
    // mirrors GeofenceAlertType.storageValue()'s identical "geofence_" +
    // wireValue() mapping, kept here instead of on GeofenceAlertType itself
    // so that enum stays free of a dependency on the alerts package.
    private static AlertType toSilenceAlertType(GeofenceAlertType type) {
        return switch (type) {
            case ENTER -> AlertType.GEOFENCE_ENTER;
            case EXIT -> AlertType.GEOFENCE_EXIT;
            case DWELL -> AlertType.GEOFENCE_DWELL;
        };
    }

    private Map<UUID, UUID> loadOrganizationIds(List<TelemetryMessage> messages) {
        List<UUID> vehicleIds = messages.stream().map(TelemetryMessage::vehicleId).distinct().toList();
        return jdbcTemplate.query(
            VEHICLE_ORGANIZATION_IDS_SQL,
            ps -> {
                Array vehicleIdArray = ps.getConnection().createArrayOf("uuid", vehicleIds.toArray());
                ps.setArray(1, vehicleIdArray);
            },
            rs -> {
                Map<UUID, UUID> organizationIdByVehicle = new HashMap<>();
                while (rs.next()) {
                    organizationIdByVehicle.put((UUID) rs.getObject("id"), (UUID) rs.getObject("organization_id"));
                }
                return organizationIdByVehicle;
            }
        );
    }
}

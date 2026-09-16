package dev.fleetpulse.processor.alerts;

import dev.fleetpulse.geocore.MotionState;
import dev.fleetpulse.processor.config.FleetpulseAlertingProperties;
import dev.fleetpulse.processor.telemetry.TelemetryMessage;
import dev.fleetpulse.processor.telemetry.VehicleMotionUpdate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Array;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

// Tasks 3.2/3.3: wired into JdbcTelemetryPositionWriter's SAME guarded live
// path GeofenceRuleDispatcher/EtaRecalculationDispatcher already use, driven
// by the exact same eligible-message subset -- see that class's own comment
// for how it is computed and why this class does not re-derive eligibility
// itself.
//
// motionUpdates is index-aligned with eligibleMessages (both produced
// together by JdbcTelemetryPositionWriter.selectLiveEligibleBatch()), so
// excessive-idle detection needs zero extra reads beyond this class's own
// silence-state lookup: motionState/lowSpeedStreakStartedAt are the SAME
// values VehicleMotionStreakTracker already computed for this exact batch,
// not a second DB round trip -- exactly this work unit's own instruction to
// reuse vehicle_state's existing motion streaks (V7) rather than invent a
// second idle-detection mechanism. vehicle_state has no speed_kmh column of
// its own, so speeding is evaluated directly off each message's own
// speedKmh, the same live reading VehicleMotionStreakTracker itself
// evaluates against MotionConfig's thresholds.
//
// One bulk silence-state read up front, then per-message decisions chained
// purely in Java against an in-memory map, then one bulk write at the end --
// mirroring VehicleMotionStreakTracker.computeUpdates()'s own "one upfront
// lookup, chain in Java, one batched write" shape, NOT GeofenceRuleDispatcher's
// own deliberate per-message round trip (that class's own comment documents
// its cost is bounded by relevantGeofenceIds, typically empty; speeding/
// excessive-idle have no equivalent bound, since EVERY eligible message
// needs a decision). See JdbcAlertSilenceStateStore's own comment for the
// perf regression this shape fixes (TelemetryEndToEndIngestTest's 1,000-message
// burst test, caught before this work unit shipped).
@Component
public class AlertRuleDispatcher {

    private static final Logger log = LoggerFactory.getLogger(AlertRuleDispatcher.class);

    private static final String VEHICLE_ORGANIZATION_IDS_SQL = "SELECT id, organization_id FROM vehicles WHERE id = ANY (?)";
    private static final List<AlertType> VEHICLE_LEVEL_ALERT_TYPES = List.of(AlertType.SPEEDING, AlertType.EXCESSIVE_IDLE);

    private final JdbcTemplate jdbcTemplate;
    private final FleetpulseAlertingProperties alertingProperties;
    private final JdbcAlertWriter alertWriter;
    private final AlertPublisher alertPublisher;
    private final JdbcAlertSilenceStateStore silenceStateStore;

    public AlertRuleDispatcher(
        JdbcTemplate jdbcTemplate,
        FleetpulseAlertingProperties alertingProperties,
        JdbcAlertWriter alertWriter,
        AlertPublisher alertPublisher,
        JdbcAlertSilenceStateStore silenceStateStore
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.alertingProperties = alertingProperties;
        this.alertWriter = alertWriter;
        this.alertPublisher = alertPublisher;
        this.silenceStateStore = silenceStateStore;
    }

    public void evaluateAndDispatch(List<TelemetryMessage> eligibleMessages, List<VehicleMotionUpdate> motionUpdates) {
        if (eligibleMessages.isEmpty()) {
            return;
        }
        Map<UUID, UUID> organizationIdByVehicle = loadOrganizationIds(eligibleMessages);
        Set<UUID> vehicleIds = organizationIdByVehicle.keySet();
        Map<AlertSilenceKey, AlertSilenceState> silenceStates =
            new HashMap<>(silenceStateStore.loadBulk(vehicleIds, VEHICLE_LEVEL_ALERT_TYPES));

        for (int i = 0; i < eligibleMessages.size(); i++) {
            TelemetryMessage message = eligibleMessages.get(i);
            VehicleMotionUpdate motionUpdate = motionUpdates.get(i);
            UUID organizationId = organizationIdByVehicle.get(message.vehicleId());
            if (organizationId == null) {
                // Defensive only, same reasoning as GeofenceRuleDispatcher's
                // own identical guard: every row in `vehicles` has a NOT
                // NULL organization_id FK, so this means the vehicle itself
                // no longer exists.
                continue;
            }
            evaluateRule(AlertType.SPEEDING, organizationId, message, isSpeeding(message), silenceStates);
            evaluateRule(AlertType.EXCESSIVE_IDLE, organizationId, message, isExcessivelyIdle(message, motionUpdate), silenceStates);
        }

        silenceStateStore.writeBulk(silenceStates);
    }

    private boolean isSpeeding(TelemetryMessage message) {
        return message.speedKmh() != null && message.speedKmh() > alertingProperties.speedLimitKmh();
    }

    private boolean isExcessivelyIdle(TelemetryMessage message, VehicleMotionUpdate motionUpdate) {
        Instant idleSince = motionUpdate.lowSpeedStreakStartedAt();
        return motionUpdate.motionState() == MotionState.IDLING
            && idleSince != null
            && Duration.between(idleSince, message.recordedAt()).compareTo(alertingProperties.excessiveIdleThreshold()) >= 0;
    }

    private void evaluateRule(
        AlertType type, UUID organizationId, TelemetryMessage message, boolean conditionActive,
        Map<AlertSilenceKey, AlertSilenceState> silenceStates
    ) {
        AlertSilenceKey key = new AlertSilenceKey(message.vehicleId(), type, null);
        AlertSilenceState previous = silenceStates.getOrDefault(key, AlertSilenceState.NONE);
        AlertSilenceDecision decision =
            AlertSilenceEngine.evaluate(previous, conditionActive, message.recordedAt(), alertingProperties.silenceWindow());
        silenceStates.put(key, decision.nextState());
        if (!decision.shouldFire()) {
            return;
        }

        Alert alert = new Alert(UUID.randomUUID(), organizationId, message.vehicleId(), type, null, message.recordedAt());
        // Persisted first (the durable history/console record) before the
        // best-effort MQTT publish below, the same ordering
        // GeofenceRuleDispatcher/EtaRecalculationDispatcher already
        // established: a temporarily unreachable broker must never cost an
        // entry in the alert history or break telemetry ingestion.
        alertWriter.write(alert);
        try {
            alertPublisher.publish(alert);
        } catch (RuntimeException ex) {
            log.warn("Failed to publish {} alert for vehicle {}", type, message.vehicleId(), ex);
        }
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

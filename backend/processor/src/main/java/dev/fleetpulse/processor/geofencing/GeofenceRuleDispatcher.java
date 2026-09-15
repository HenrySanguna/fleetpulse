package dev.fleetpulse.processor.geofencing;

import dev.fleetpulse.geocore.FenceMembershipConfig;
import dev.fleetpulse.geocore.FenceMembershipSample;
import dev.fleetpulse.geocore.FenceMembershipState;
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
@Component
public class GeofenceRuleDispatcher {

    private static final Logger log = LoggerFactory.getLogger(GeofenceRuleDispatcher.class);

    private static final String VEHICLE_ORGANIZATION_IDS_SQL = "SELECT id, organization_id FROM vehicles WHERE id = ANY (?)";

    private final JdbcTemplate jdbcTemplate;
    private final GeofenceEvaluator geofenceEvaluator;
    private final FleetpulseGeofencingProperties geofencingProperties;
    private final GeofenceAlertPublisher alertPublisher;
    private final JdbcVehicleFenceStateWriter fenceStateWriter;
    private final JdbcGeofenceAlertWriter alertWriter;

    public GeofenceRuleDispatcher(
        JdbcTemplate jdbcTemplate,
        GeofenceEvaluator geofenceEvaluator,
        FleetpulseGeofencingProperties geofencingProperties,
        GeofenceAlertPublisher alertPublisher,
        JdbcVehicleFenceStateWriter fenceStateWriter,
        JdbcGeofenceAlertWriter alertWriter
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.geofenceEvaluator = geofenceEvaluator;
        this.geofencingProperties = geofencingProperties;
        this.alertPublisher = alertPublisher;
        this.fenceStateWriter = fenceStateWriter;
        this.alertWriter = alertWriter;
    }

    public void evaluateAndDispatch(List<TelemetryMessage> eligibleMessages) {
        if (eligibleMessages.isEmpty()) {
            return;
        }
        Map<UUID, UUID> organizationIdByVehicle = loadOrganizationIds(eligibleMessages);
        FenceMembershipConfig config = new FenceMembershipConfig(
            geofencingProperties.confirmationReadings(), geofencingProperties.confirmationDuration()
        );
        for (TelemetryMessage message : eligibleMessages) {
            UUID organizationId = organizationIdByVehicle.get(message.vehicleId());
            if (organizationId == null) {
                // Defensive only: every row in `vehicles` has a NOT NULL
                // organization_id FK, so this means the vehicle itself no
                // longer exists (a resend racing a hard delete) -- nothing
                // to evaluate it against.
                continue;
            }
            processMessage(organizationId, message, config);
        }
    }

    private void processMessage(UUID organizationId, TelemetryMessage message, FenceMembershipConfig config) {
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
            dispatchForGeofence(organizationId, message, config, geofenceId, rule, insideStrict, insideBufferedFromQuery, previousStates);
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
        Map<UUID, GeofenceMembershipRecord> previousStates
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

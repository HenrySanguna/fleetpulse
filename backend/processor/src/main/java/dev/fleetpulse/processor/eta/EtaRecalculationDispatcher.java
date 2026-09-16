package dev.fleetpulse.processor.eta;

import dev.fleetpulse.geocore.GeoPoint;
import dev.fleetpulse.processor.config.FleetpulseEtaProperties;
import dev.fleetpulse.processor.telemetry.TelemetryMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// Task 2.4 ("recalculo en el flujo en vivo y publicacion al topico de la
// organizacion"): wired into JdbcTelemetryPositionWriter's SAME guarded live
// write path GeofenceRuleDispatcher already uses, driven by the exact same
// "newer than the last known state" eligible-message subset -- see
// JdbcTelemetryPositionWriter's own class comment for how that subset is
// computed and why this class does not re-derive eligibility itself, the
// same convention VehicleMotionStreakTracker/GeofenceRuleDispatcher already
// follow.
//
// Unlike GeofenceRuleDispatcher's own deliberate one-round-trip-per-message
// shape (needed there for same-batch read-after-write chaining across
// multiple geofences per vehicle), ETA recalculation has no such ordering
// dependency: each eligible message's estimate depends only on that
// message's own position and the vehicle's destination/recent speed, never
// on a previous message's own ETA. loadActiveDestinations() is still one
// upfront batch lookup (not per-message) for the same reason every other
// live-path reader in this module already does that.
@Component
public class EtaRecalculationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(EtaRecalculationDispatcher.class);

    private final JdbcVehicleDestinationReader destinationReader;
    private final JdbcRecentSpeedReader recentSpeedReader;
    private final EtaCalculator etaCalculator;
    private final JdbcVehicleDestinationEtaWriter etaWriter;
    private final EtaPublisher etaPublisher;
    private final FleetpulseEtaProperties etaProperties;

    public EtaRecalculationDispatcher(
        JdbcVehicleDestinationReader destinationReader,
        JdbcRecentSpeedReader recentSpeedReader,
        EtaCalculator etaCalculator,
        JdbcVehicleDestinationEtaWriter etaWriter,
        EtaPublisher etaPublisher,
        FleetpulseEtaProperties etaProperties
    ) {
        this.destinationReader = destinationReader;
        this.recentSpeedReader = recentSpeedReader;
        this.etaCalculator = etaCalculator;
        this.etaWriter = etaWriter;
        this.etaPublisher = etaPublisher;
        this.etaProperties = etaProperties;
    }

    public void recalculateAndDispatch(List<TelemetryMessage> eligibleMessages) {
        if (eligibleMessages.isEmpty()) {
            return;
        }
        List<UUID> vehicleIds = eligibleMessages.stream().map(TelemetryMessage::vehicleId).distinct().toList();
        Map<UUID, VehicleDestination> destinations = destinationReader.loadActiveDestinations(vehicleIds);
        if (destinations.isEmpty()) {
            // Cheap short-circuit: most live traffic belongs to vehicles
            // with no destination assigned at all.
            return;
        }
        for (TelemetryMessage message : eligibleMessages) {
            VehicleDestination destination = destinations.get(message.vehicleId());
            if (destination == null) {
                continue;
            }
            recalculateForVehicle(destination, message);
        }
    }

    private void recalculateForVehicle(VehicleDestination destination, TelemetryMessage message) {
        GeoPoint currentPosition = new GeoPoint(message.lat(), message.lon(), message.recordedAt());
        GeoPoint destinationPoint = new GeoPoint(destination.lat(), destination.lon(), message.recordedAt());

        double recentAverageSpeedKmh = recentSpeedReader
            .averageSpeedKmh(message.vehicleId(), message.recordedAt(), etaProperties.recentSpeedWindow())
            .orElse(etaProperties.fallbackAverageSpeedKmh());

        EtaEstimate estimate = etaCalculator.calculate(currentPosition, destinationPoint, recentAverageSpeedKmh);

        etaWriter.updateEta(destination.vehicleId(), estimate, message.recordedAt());

        // Persisted first (vehicle_destinations, the durable value the
        // snapshot endpoint serves) before the best-effort MQTT publish, the
        // same ordering GeofenceRuleDispatcher already established for
        // alerts: a temporarily unreachable broker must never cost the
        // computed estimate itself, and must never break telemetry
        // ingestion either.
        try {
            etaPublisher.publish(destination.organizationId(), destination.vehicleId(), estimate, message.recordedAt());
        } catch (RuntimeException ex) {
            log.warn("Failed to publish ETA for vehicle {}", destination.vehicleId(), ex);
        }
    }
}

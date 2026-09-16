package dev.fleetpulse.processor.trips;

import dev.fleetpulse.processor.config.FleetpulseTripsProperties;
import dev.fleetpulse.processor.telemetry.TelemetryMessage;
import dev.fleetpulse.processor.telemetry.VehicleMotionStreakTracker;
import dev.fleetpulse.processor.telemetry.VehicleMotionUpdate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;

// Task 1.2: runs on its own schedule, entirely over already-persisted
// `positions` -- never on the live MQTT ingestion path (design.md: "no en
// el camino de ingesta ... mantener la ruta de escritura centrada en
// escribir rapido es mas importante"). Every 5 minutes is this change's own
// choice (neither proposal.md nor design.md states an exact frequency):
// frequent enough that a newly-closed trip shows up reasonably soon,
// infrequent enough not to re-scan the same still-open tail needlessly
// often.
//
// Reuses the SAME VehicleMotionStreakTracker bean (and therefore the SAME
// MotionConfig thresholds/debounce) the live write path
// (JdbcTelemetryPositionWriter) already uses for motion_state, replayed
// from an empty "known states" map: the queried window always starts
// either at a vehicle's very first ever position (an "unseen vehicle",
// exactly VehicleMotionStreakTracker's own existing default) or right
// where the previous CLOSED trip left off -- i.e. at or just inside the
// stop that closed it -- so seeding fresh is correct, not an
// approximation. See TripSegmenter's own class comment for the full
// design-gap resolution this rests on.
@Component
public class TripSegmentationTask {

    private static final Logger log = LoggerFactory.getLogger(TripSegmentationTask.class);

    private final JdbcTripReader tripReader;
    private final JdbcTripWriter tripWriter;
    private final VehicleMotionStreakTracker motionStreakTracker;
    private final FleetpulseTripsProperties tripsProperties;

    public TripSegmentationTask(
        JdbcTripReader tripReader,
        JdbcTripWriter tripWriter,
        VehicleMotionStreakTracker motionStreakTracker,
        FleetpulseTripsProperties tripsProperties
    ) {
        this.tripReader = tripReader;
        this.tripWriter = tripWriter;
        this.motionStreakTracker = motionStreakTracker;
        this.tripsProperties = tripsProperties;
    }

    @Scheduled(fixedDelayString = "PT5M")
    public void segmentAllVehicles() {
        Instant horizon = Instant.now().minus(tripsProperties.processingDelay());
        List<JdbcTripReader.VehicleThreshold> vehicles = tripReader.loadVehiclesWithThreshold();
        int closedTrips = 0;
        for (JdbcTripReader.VehicleThreshold vehicle : vehicles) {
            closedTrips += segmentVehicle(vehicle, horizon);
        }
        log.info("Trip segmentation run closed {} trip(s) across {} vehicle(s)", closedTrips, vehicles.size());
    }

    private int segmentVehicle(JdbcTripReader.VehicleThreshold vehicle, Instant horizon) {
        Instant since = tripReader.lastClosedTripEndedAt(vehicle.vehicleId());
        List<PositionSample> positions = tripReader.positionsSince(vehicle.vehicleId(), since, horizon);
        if (positions.isEmpty()) {
            return 0;
        }

        List<TelemetryMessage> asMessages = positions.stream()
            .map(p -> new TelemetryMessage(vehicle.vehicleId(), p.recordedAt(), p.lat(), p.lon(), p.speedKmh(), null, p.ignition()))
            .toList();
        List<VehicleMotionUpdate> motionUpdates = motionStreakTracker.computeUpdates(Map.of(), asMessages);

        List<TripCandidate> trips = TripSegmenter.segment(
            vehicle.vehicleId(), vehicle.organizationId(), positions, motionUpdates, vehicle.stopThreshold()
        );
        tripWriter.writeBatch(trips);
        return trips.size();
    }
}

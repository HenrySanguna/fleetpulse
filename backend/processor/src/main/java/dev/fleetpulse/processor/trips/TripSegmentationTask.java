package dev.fleetpulse.processor.trips;

import dev.fleetpulse.geocore.PositionSample;
import dev.fleetpulse.processor.config.FleetpulseTripsProperties;
import dev.fleetpulse.processor.telemetry.TelemetryActivity;
import dev.fleetpulse.processor.telemetry.TelemetryMessage;
import dev.fleetpulse.processor.telemetry.VehicleMotionStreakTracker;
import dev.fleetpulse.processor.telemetry.VehicleMotionUpdate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
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
    private final TelemetryActivity telemetryActivity;
    private final Clock clock;

    // Idle-run gate state, only touched by the scheduler thread. See
    // segmentWhenNeeded().
    private boolean hasRun = false;
    private boolean lastRunClosedNothing = false;
    private long lastRunActivityVersion = 0;
    private Instant lastRunHorizon = Instant.EPOCH;

    public TripSegmentationTask(
        JdbcTripReader tripReader,
        JdbcTripWriter tripWriter,
        VehicleMotionStreakTracker motionStreakTracker,
        FleetpulseTripsProperties tripsProperties
    ) {
        this(tripReader, tripWriter, motionStreakTracker, tripsProperties, new TelemetryActivity(), Clock.systemUTC());
    }

    @Autowired
    public TripSegmentationTask(
        JdbcTripReader tripReader,
        JdbcTripWriter tripWriter,
        VehicleMotionStreakTracker motionStreakTracker,
        FleetpulseTripsProperties tripsProperties,
        TelemetryActivity telemetryActivity
    ) {
        this(tripReader, tripWriter, motionStreakTracker, tripsProperties, telemetryActivity, Clock.systemUTC());
    }

    public TripSegmentationTask(
        JdbcTripReader tripReader,
        JdbcTripWriter tripWriter,
        VehicleMotionStreakTracker motionStreakTracker,
        FleetpulseTripsProperties tripsProperties,
        TelemetryActivity telemetryActivity,
        Clock clock
    ) {
        this.tripReader = tripReader;
        this.tripWriter = tripWriter;
        this.motionStreakTracker = motionStreakTracker;
        this.tripsProperties = tripsProperties;
        this.telemetryActivity = telemetryActivity;
        this.clock = clock;
    }

    // The database is Neon, which only scales compute to zero after ~5
    // minutes without activity, so a run is skipped only when it provably
    // cannot change anything. A run reads exactly the positions recorded up
    // to `horizon` (after each vehicle's last closed trip) and is otherwise
    // deterministic, so the next run has the same input -- and therefore the
    // same (empty) result -- when all of these hold:
    //  - the previous run closed no trip (a closed trip moves the watermark,
    //    changing the next run's input, so one more run is needed);
    //  - this process persisted no positions since (the activity version is
    //    unchanged), which also covers late or out-of-order arrivals;
    //  - no position can exist beyond the previous run's horizon, i.e. the
    //    delay has already caught up with the newest known position.
    // The first run after startup always executes (this state is in memory).
    @Scheduled(fixedDelayString = "PT5M")
    public void segmentWhenNeeded() {
        if (!hasRun) {
            telemetryActivity.seedFromDatabase(tripReader.latestPositionRecordedAt());
        }
        TelemetryActivity.Snapshot activity = telemetryActivity.snapshot();
        Instant horizon = clock.instant().minus(tripsProperties.processingDelay());

        boolean provablyNoOp = hasRun
            && lastRunClosedNothing
            && activity.version() == lastRunActivityVersion
            && !activity.pendingBound().isAfter(lastRunHorizon);
        if (provablyNoOp) {
            log.debug("Trip segmentation run skipped: no new telemetry and nothing pending");
            return;
        }

        int closedTrips = segmentAllVehicles(horizon);
        hasRun = true;
        lastRunClosedNothing = closedTrips == 0;
        lastRunActivityVersion = activity.version();
        lastRunHorizon = horizon;
    }

    public int segmentAllVehicles() {
        return segmentAllVehicles(clock.instant().minus(tripsProperties.processingDelay()));
    }

    private int segmentAllVehicles(Instant horizon) {
        List<JdbcTripReader.VehicleThreshold> vehicles = tripReader.loadVehiclesWithThreshold();
        int closedTrips = 0;
        for (JdbcTripReader.VehicleThreshold vehicle : vehicles) {
            closedTrips += segmentVehicle(vehicle, horizon);
        }
        log.info("Trip segmentation run closed {} trip(s) across {} vehicle(s)", closedTrips, vehicles.size());
        return closedTrips;
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

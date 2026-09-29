package dev.fleetpulse.processor.trips;

import dev.fleetpulse.geocore.MotionState;
import dev.fleetpulse.geocore.PositionSample;
import dev.fleetpulse.geocore.TripSegmentRules;
import dev.fleetpulse.geocore.TripSegmentRules.Segment;
import dev.fleetpulse.processor.telemetry.VehicleMotionUpdate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

// Tasks 1.2/1.4 (design.md "Segmentacion de viajes"): "un viaje empieza
// cuando el vehiculo pasa a moving y termina cuando permanece en stopped
// mas de un umbral" is MotionState vocabulary (geo-core's own MOVING /
// IDLING / STOPPED, change 01, already replayed live by
// VehicleMotionStreakTracker since change 03) -- not a gap in telemetry
// reporting. Resolved here, documented per this project's "note
// deviations, don't silently freelance" convention since design.md never
// spells out the mechanism: TripSegmentationTask replays each vehicle's
// window of positions through the SAME VehicleMotionStreakTracker bean the
// live write path already uses (same MotionConfig thresholds/debounce), so
// a trip's boundaries are decided from the exact MOVING/IDLING/STOPPED
// classification vehicle_state.motion_state would show if this window were
// being processed live -- not a second, parallel speed-threshold heuristic.
//
// The actual stop/dwell state machine and per-span distance/idle/speed
// metrics live in geo-core's TripSegmentRules (shared trip rules change):
// api's InProgressTripCalculator replays the exact same rules for its own
// still-open trailing span. This class only maps TripSegmentRules' CLOSED
// segments into persisted TripCandidate rows -- the still-OPEN trailing
// segment TripSegmentRules may report (an active trip, or a not-yet-
// threshold-long trailing stop) is deliberately never emitted here: this
// run cannot yet know whether that stop will end up qualifying, or whether
// the vehicle resumes moving again soon. Leaving it unemitted is what makes
// reprocessing idempotent (task 1.5): the next run's watermark stays at the
// last CLOSED trip's ended_at, so it naturally re-reads and re-evaluates
// that same trailing span once more real time (design.md's deliberate
// processing delay) has passed.
public final class TripSegmenter {

    private TripSegmenter() {
    }

    public static List<TripCandidate> segment(
        UUID vehicleId,
        UUID organizationId,
        List<PositionSample> positions,
        List<VehicleMotionUpdate> motionUpdates,
        Duration stopThreshold
    ) {
        if (positions.size() != motionUpdates.size()) {
            throw new IllegalArgumentException("positions and motionUpdates must be parallel lists of the same size");
        }

        List<MotionState> motionStates = motionUpdates.stream().map(VehicleMotionUpdate::motionState).toList();
        List<Instant> recordedAt = positions.stream().map(PositionSample::recordedAt).toList();

        List<TripCandidate> trips = new ArrayList<>();
        for (Segment segment : TripSegmentRules.segment(motionStates, recordedAt, stopThreshold)) {
            if (!segment.isOpen()) {
                trips.add(toTripCandidate(vehicleId, organizationId, positions, motionStates, segment));
            }
        }
        return trips;
    }

    private static TripCandidate toTripCandidate(
        UUID vehicleId, UUID organizationId, List<PositionSample> positions, List<MotionState> motionStates, Segment segment
    ) {
        Instant startedAt = positions.get(segment.startIdx()).recordedAt();
        TripSegmentRules.Metrics metrics = TripSegmentRules.metrics(positions, motionStates, segment.startIdx(), segment.endIdx(), startedAt);
        return new TripCandidate(
            vehicleId, organizationId, startedAt, positions.get(segment.endIdx()).recordedAt(),
            metrics.distanceKm(), metrics.durationSecs(), metrics.idleSecs(), metrics.maxSpeedKmh(), metrics.avgSpeedKmh()
        );
    }
}

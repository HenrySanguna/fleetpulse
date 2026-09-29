package dev.fleetpulse.api.reports;

import dev.fleetpulse.geocore.MotionConfig;
import dev.fleetpulse.geocore.MotionReplay;
import dev.fleetpulse.geocore.MotionState;
import dev.fleetpulse.geocore.PositionSample;
import dev.fleetpulse.geocore.TripSegmentRules;
import dev.fleetpulse.geocore.TripSegmentRules.Metrics;
import dev.fleetpulse.geocore.TripSegmentRules.Segment;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

// Task 10: display-only recomputation of "is this vehicle currently inside
// an unclosed trip, and if so what does that trip look like so far" --
// deliberately never written to `trips`, only ever returned in the report
// response.
//
// The stop/dwell state machine and per-span distance/idle/speed metrics are
// shared with processor's TripSegmenter via geo-core's TripSegmentRules
// (shared trip rules change) -- api cannot depend on processor, so neither
// piece is reused directly, but both now replay the exact same geo-core
// rules instead of two hand-kept-in-sync copies. Likewise,
// replayMotionStates() shares its streak/debounce computation with
// processor's VehicleMotionStreakTracker via geo-core's MotionReplay: the
// only mode TripSegmentationTask itself ever actually exercises is a fresh
// replay from an empty known-state map, which is exactly what this method
// does for a single vehicle. `positions` here is already strictly ascending
// by recorded_at (InProgressTripJdbcReader's own ORDER BY), so unlike the
// general live path this never needs to guard against an out-of-order
// message.
//
// Only the FINAL open segment (if any) TripSegmentRules reports matters for
// this feature; any segment that same rule would have already fully closed
// along the way is left for the real TripSegmentationTask to persist on its
// own next run (display lag, not a correctness gap: it will appear in
// `trips` once the processor catches up).
final class InProgressTripCalculator {

    private InProgressTripCalculator() {
    }

    // windowStart is the lower bound actually queried (max(last closed
    // trip's ended_at, the report's own `from`)); clippedToWindowStart is
    // true when `from` won the max, i.e. the vehicle's real trip start (if
    // any) might predate what this window can see. In that case, and only
    // when the trip turns out to already be open at index 0, startedAt is
    // reported as windowStart itself ("or the window start", task 10's own
    // wording) rather than the first sample's timestamp, which would
    // otherwise understate how long the vehicle has actually been moving.
    static Optional<ActivityInProgressTripResponse> calculate(
        List<PositionSample> positions,
        Instant windowStart,
        boolean clippedToWindowStart,
        Duration stopThreshold,
        MotionConfig motionConfig
    ) {
        if (positions.isEmpty()) {
            return Optional.empty();
        }

        List<MotionState> states = replayMotionStates(positions, motionConfig);
        List<Instant> recordedAt = positions.stream().map(PositionSample::recordedAt).toList();

        List<Segment> segments = TripSegmentRules.segment(states, recordedAt, stopThreshold);
        if (segments.isEmpty()) {
            return Optional.empty();
        }

        Segment lastSegment = segments.get(segments.size() - 1);
        if (!lastSegment.isOpen()) {
            return Optional.empty();
        }

        int tripStartIdx = lastSegment.startIdx();
        int lastIdx = positions.size() - 1;
        Instant startedAt = tripStartIdx == 0 && clippedToWindowStart ? windowStart : positions.get(tripStartIdx).recordedAt();

        Metrics metrics = TripSegmentRules.metrics(positions, states, tripStartIdx, lastIdx, startedAt);

        return Optional.of(new ActivityInProgressTripResponse(
            startedAt, metrics.distanceKm(), (int) (metrics.durationSecs() / 60), (int) (metrics.idleSecs() / 60), metrics.maxSpeedKmh()
        ));
    }

    private static List<MotionState> replayMotionStates(List<PositionSample> positions, MotionConfig config) {
        List<MotionState> states = new ArrayList<>(positions.size());
        MotionState previousState = null;
        Instant previousLow = null;
        Instant previousHigh = null;

        for (PositionSample position : positions) {
            boolean engineOn = Boolean.TRUE.equals(position.ignition());
            MotionReplay.Transition transition = MotionReplay.next(
                previousState, previousLow, previousHigh, position.speedKmh(), engineOn, position.recordedAt(), config
            );
            states.add(transition.motionState());
            previousState = transition.motionState();
            previousLow = transition.lowSpeedStreakStartedAt();
            previousHigh = transition.highSpeedStreakStartedAt();
        }
        return states;
    }
}

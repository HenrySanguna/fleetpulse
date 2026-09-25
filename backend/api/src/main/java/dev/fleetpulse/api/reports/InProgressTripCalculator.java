package dev.fleetpulse.api.reports;

import dev.fleetpulse.api.reports.InProgressTripJdbcReader.MotionPositionSample;
import dev.fleetpulse.geocore.Geo;
import dev.fleetpulse.geocore.GeoPoint;
import dev.fleetpulse.geocore.MotionConfig;
import dev.fleetpulse.geocore.MotionDetector;
import dev.fleetpulse.geocore.MotionSample;
import dev.fleetpulse.geocore.MotionState;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

// Task 10: display-only recomputation of "is this vehicle currently inside
// an unclosed trip, and if so what does that trip look like so far" --
// deliberately never written to `trips`, only ever returned in the report
// response. Two things are mirrored here from the processor module by hand
// (documented deviation: api cannot depend on processor, and neither piece
// is small enough on its own to extract into geo-core/domain without also
// moving TelemetryMessage/VehicleMotionUpdate and every other processor
// call site that reads them -- alerts, eta, geofencing, rollups):
//
// 1. replayMotionStates() mirrors processor/.../telemetry/
//    VehicleMotionStreakTracker.nextFor()'s fresh-vehicle replay -- the
//    only mode TripSegmentationTask itself ever actually exercises: it
//    always calls computeUpdates(Map.of(), ...), never carrying a vehicle's
//    known streak across scheduled runs. `positions` here is already
//    strictly ascending by recorded_at (InProgressTripJdbcReader's own
//    ORDER BY), so unlike the general tracker this never needs to guard
//    against an out-of-order message.
// 2. calculate()'s main loop mirrors processor/.../trips/
//    TripSegmenter.segment()'s own tripStartIdx/notMovingRunStartIdx state
//    machine -- only the FINAL open tripStartIdx (if any) matters for this
//    feature; any trip that same loop would have already fully closed
//    along the way is left for the real TripSegmentationTask to persist on
//    its own next run (display lag, not a correctness gap: it will appear
//    in `trips` once the processor catches up).
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
        List<MotionPositionSample> positions,
        Instant windowStart,
        boolean clippedToWindowStart,
        Duration stopThreshold,
        MotionConfig motionConfig
    ) {
        if (positions.isEmpty()) {
            return Optional.empty();
        }

        List<MotionState> states = replayMotionStates(positions, motionConfig);

        Integer tripStartIdx = 0;
        Integer notMovingRunStartIdx = null;
        boolean runAlreadyClosedTrip = false;
        for (int i = 0; i < positions.size(); i++) {
            boolean isNotMoving = isNotMoving(states.get(i));
            if (isNotMoving) {
                if (notMovingRunStartIdx == null) {
                    notMovingRunStartIdx = i;
                    runAlreadyClosedTrip = false;
                }
                Duration runDuration = Duration.between(positions.get(notMovingRunStartIdx).recordedAt(), positions.get(i).recordedAt());
                if (!runAlreadyClosedTrip && runDuration.compareTo(stopThreshold) >= 0) {
                    tripStartIdx = null;
                    runAlreadyClosedTrip = true;
                }
            } else {
                notMovingRunStartIdx = null;
                if (tripStartIdx == null) {
                    tripStartIdx = i;
                }
            }
        }

        if (tripStartIdx == null) {
            return Optional.empty();
        }

        int lastIdx = positions.size() - 1;
        if (lastIdx - tripStartIdx < 1) {
            // Same single-position guard as TripSegmenter.addTrip(): no
            // distance/duration worth showing yet.
            return Optional.empty();
        }

        Instant startedAt = tripStartIdx == 0 && clippedToWindowStart ? windowStart : positions.get(tripStartIdx).recordedAt();

        double distanceMeters = 0.0;
        long idleSecs = 0;
        double maxSpeedKmh = 0.0;
        for (int i = tripStartIdx + 1; i <= lastIdx; i++) {
            MotionPositionSample previous = positions.get(i - 1);
            MotionPositionSample current = positions.get(i);
            GeoPoint previousPoint = new GeoPoint(previous.lat(), previous.lon(), previous.recordedAt());
            GeoPoint currentPoint = new GeoPoint(current.lat(), current.lon(), current.recordedAt());
            distanceMeters += Geo.distanceMeters(previousPoint, currentPoint);

            if (previous.speedKmh() != null) {
                maxSpeedKmh = Math.max(maxSpeedKmh, previous.speedKmh());
            }
            if (current.speedKmh() != null) {
                maxSpeedKmh = Math.max(maxSpeedKmh, current.speedKmh());
            }

            if (isNotMoving(states.get(i - 1)) && isNotMoving(states.get(i))) {
                idleSecs += Duration.between(previous.recordedAt(), current.recordedAt()).toSeconds();
            }
        }

        long durationSecs = Duration.between(startedAt, positions.get(lastIdx).recordedAt()).toSeconds();
        double distanceKm = distanceMeters / 1000.0;

        return Optional.of(new ActivityInProgressTripResponse(
            startedAt, distanceKm, (int) (durationSecs / 60), (int) (idleSecs / 60), maxSpeedKmh
        ));
    }

    private static List<MotionState> replayMotionStates(List<MotionPositionSample> positions, MotionConfig config) {
        List<MotionState> states = new ArrayList<>(positions.size());
        MotionState previousState = null;
        Instant lowStreakStartedAt = null;
        Instant highStreakStartedAt = null;

        for (MotionPositionSample position : positions) {
            Double speedKmh = position.speedKmh();
            if (speedKmh == null) {
                // No speed to evaluate -- carry the last known state (or
                // null, if nothing is known yet) forward unchanged, same as
                // VehicleMotionStreakTracker.nextFor()'s own null-speed branch.
                states.add(previousState);
                continue;
            }

            MotionState prevOrDefault = previousState == null ? MotionState.STOPPED : previousState;
            boolean belowStopThreshold = speedKmh < config.stopThresholdKmh();
            boolean aboveStartThreshold = speedKmh >= config.startThresholdKmh();

            lowStreakStartedAt = belowStopThreshold ? (lowStreakStartedAt != null ? lowStreakStartedAt : position.recordedAt()) : null;
            highStreakStartedAt = aboveStartThreshold ? (highStreakStartedAt != null ? highStreakStartedAt : position.recordedAt()) : null;

            Duration lowSpeedStreak = belowStopThreshold ? Duration.between(lowStreakStartedAt, position.recordedAt()) : Duration.ZERO;
            Duration highSpeedStreak = aboveStartThreshold ? Duration.between(highStreakStartedAt, position.recordedAt()) : Duration.ZERO;
            boolean engineOn = Boolean.TRUE.equals(position.ignition());

            MotionState nextState = MotionDetector.next(prevOrDefault, new MotionSample(speedKmh, engineOn, lowSpeedStreak, highSpeedStreak), config);
            states.add(nextState);
            previousState = nextState;
        }
        return states;
    }

    private static boolean isNotMoving(MotionState state) {
        return state == MotionState.STOPPED || state == MotionState.IDLING;
    }
}

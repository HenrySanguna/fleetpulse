package dev.fleetpulse.geocore;

import java.time.Duration;
import java.time.Instant;

// Shared streak-tracking scaffolding around MotionDetector.next(): both
// processor's VehicleMotionStreakTracker (persisted, per-vehicle, live
// telemetry) and api's InProgressTripCalculator (display-only, replayed
// fresh from an unknown state on every report request) fold one
// PositionSample's speed/ignition into the next MotionState the exact same
// way -- extracted here (shared trip rules change) instead of two
// hand-kept-in-sync copies.
//
// A position with no speedKmh cannot be evaluated by MotionDetector, which
// requires a primitive double: state and both streaks are simply carried
// forward unchanged (or left null if nothing is known yet) rather than
// guessing.
public final class MotionReplay {

    private MotionReplay() {
    }

    public static Transition next(
        MotionState previousState,
        Instant previousLowSpeedStreakStartedAt,
        Instant previousHighSpeedStreakStartedAt,
        Double speedKmh,
        boolean engineOn,
        Instant recordedAt,
        MotionConfig config
    ) {
        if (speedKmh == null) {
            return new Transition(previousState, previousLowSpeedStreakStartedAt, previousHighSpeedStreakStartedAt);
        }

        MotionState prevOrDefault = previousState == null ? MotionState.STOPPED : previousState;
        boolean belowStopThreshold = speedKmh < config.stopThresholdKmh();
        boolean aboveStartThreshold = speedKmh >= config.startThresholdKmh();

        Instant lowStreakStartedAt = belowStopThreshold ? continuedOrStarted(previousLowSpeedStreakStartedAt, recordedAt) : null;
        Instant highStreakStartedAt = aboveStartThreshold ? continuedOrStarted(previousHighSpeedStreakStartedAt, recordedAt) : null;

        Duration lowSpeedStreak = belowStopThreshold ? Duration.between(lowStreakStartedAt, recordedAt) : Duration.ZERO;
        Duration highSpeedStreak = aboveStartThreshold ? Duration.between(highStreakStartedAt, recordedAt) : Duration.ZERO;

        MotionState nextState = MotionDetector.next(prevOrDefault, new MotionSample(speedKmh, engineOn, lowSpeedStreak, highSpeedStreak), config);
        return new Transition(nextState, lowStreakStartedAt, highStreakStartedAt);
    }

    private static Instant continuedOrStarted(Instant existingStreakStartedAt, Instant recordedAt) {
        return existingStreakStartedAt != null ? existingStreakStartedAt : recordedAt;
    }

    public record Transition(MotionState motionState, Instant lowSpeedStreakStartedAt, Instant highSpeedStreakStartedAt) {
    }
}

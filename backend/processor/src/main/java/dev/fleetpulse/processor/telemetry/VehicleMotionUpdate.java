package dev.fleetpulse.processor.telemetry;

import dev.fleetpulse.geocore.MotionState;

import java.time.Instant;

// What VehicleMotionStreakTracker computes for one telemetry message: the
// values JdbcTelemetryPositionWriter binds into the guarded
// motion_state/low_speed_streak_started_at/high_speed_streak_started_at
// columns of the same upsert task 4.1 built. Whether this update is ever
// actually applied is decided entirely by that upsert's existing
// `WHERE vehicle_state.recorded_at IS NULL OR vehicle_state.recorded_at < excluded.recorded_at`
// guard -- this record carries no opinion on that; it is computed the same
// way regardless, exactly like location/recorded_at already are.
public record VehicleMotionUpdate(
    MotionState motionState,
    Instant lowSpeedStreakStartedAt,
    Instant highSpeedStreakStartedAt
) {
}

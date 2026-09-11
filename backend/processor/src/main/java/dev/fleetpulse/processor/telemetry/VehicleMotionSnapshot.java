package dev.fleetpulse.processor.telemetry;

import dev.fleetpulse.geocore.MotionState;

import java.time.Instant;

// What JdbcTelemetryPositionWriter already has stored for a vehicle before
// processing a new message: the recordedAt the guard (task 4.1) compares
// against, the last computed MotionState, and the timestamp each
// currently-active streak began (V7 migration). All four fields are
// nullable -- a vehicle that has never been evaluated by VehicleMotionStreakTracker
// yet (or was only ever touched by the presence consumer's LWT testament,
// change 03 WU8) has no snapshot at all, represented by a null reference to
// this record rather than an instance full of nulls.
public record VehicleMotionSnapshot(
    Instant recordedAt,
    MotionState motionState,
    Instant lowSpeedStreakStartedAt,
    Instant highSpeedStreakStartedAt
) {
}

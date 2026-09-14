package dev.fleetpulse.api.fleet;

import dev.fleetpulse.geocore.MotionState;

import java.time.Instant;

// Internal read projection of one vehicle_state row -- VehicleStateJdbcReader
// only, never returned from a controller (see VehicleStateResponse).
record VehicleStateRow(Double lat, Double lon, Instant recordedAt, MotionState motionState, boolean online) {
}

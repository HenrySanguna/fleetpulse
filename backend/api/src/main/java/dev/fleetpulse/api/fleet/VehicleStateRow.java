package dev.fleetpulse.api.fleet;

import dev.fleetpulse.geocore.MotionState;

import java.time.Instant;

// Internal read projection of one vehicle_state row LEFT JOINed with its
// vehicle_destinations row (task 2.1/2.4, WU2) -- VehicleStateJdbcReader
// only, never returned from a controller (see VehicleStateResponse).
// destinationLat/destinationLon/etaSeconds/etaMarginSeconds/etaCalculatedAt
// are null when the vehicle has no destination currently assigned.
record VehicleStateRow(
    Double lat,
    Double lon,
    Instant recordedAt,
    MotionState motionState,
    boolean online,
    Double destinationLat,
    Double destinationLon,
    Integer etaSeconds,
    Integer etaMarginSeconds,
    Instant etaCalculatedAt
) {
}

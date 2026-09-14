package dev.fleetpulse.api.fleet;

import dev.fleetpulse.geocore.MotionState;

import java.time.Instant;
import java.util.UUID;

// Task 2.1: response DTO for GET /api/fleet/state -- never the JPA Vehicle
// entity itself. lat/lon/recordedAt/motionState are nullable: vehicle_state
// (V5 migration) allows a vehicle to have no reported position yet (a row
// created by the presence consumer from an LWT testament before any
// telemetry ever arrives, or no vehicle_state row at all). speed/heading are
// deliberately absent -- vehicle_state has no such columns (only positions,
// the append-only history table, does); the live map gets those fields from
// the MQTT telemetry stream directly (design.md/proposal.md's whole point:
// the backend is not in the live data path), this endpoint only seeds the
// initial snapshot before that stream takes over.
public record VehicleStateResponse(
    UUID vehicleId,
    String label,
    Double lat,
    Double lon,
    Instant recordedAt,
    MotionState motionState,
    boolean online
) {
}

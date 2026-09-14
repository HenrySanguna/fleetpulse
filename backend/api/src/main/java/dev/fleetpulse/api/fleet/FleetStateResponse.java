package dev.fleetpulse.api.fleet;

import java.util.List;

// Task 2.1: wraps the collection so the shape matches design.md's
// FleetSnapshot concept (room to add fields like an "as of" timestamp later
// without breaking the generated api-client type), instead of a bare array.
public record FleetStateResponse(List<VehicleStateResponse> vehicles) {
}

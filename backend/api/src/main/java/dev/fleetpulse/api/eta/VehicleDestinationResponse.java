package dev.fleetpulse.api.eta;

import java.time.Instant;
import java.util.UUID;

// Task 2.1/2.4/2.5: response DTO for PUT/DELETE /api/vehicles/{id}/destination
// and the joined snapshot fields VehicleStateResponse carries. etaSeconds/
// etaMarginSeconds/etaCalculatedAt are nullable -- NULL until the live write
// path (processor module, EtaRecalculationDispatcher) computes the first
// estimate for a freshly assigned destination. Never the raw
// vehicle_destinations row: destination is exposed as lat/lon, never the
// PostGIS GEOGRAPHY value itself, matching VehicleStateResponse's own
// convention for vehicle_state.location.
public record VehicleDestinationResponse(
    UUID vehicleId,
    double lat,
    double lon,
    Instant assignedAt,
    Integer etaSeconds,
    Integer etaMarginSeconds,
    Instant etaCalculatedAt
) {
}

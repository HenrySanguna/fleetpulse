package dev.fleetpulse.processor.eta;

import java.util.UUID;

// One row read back from vehicle_destinations (V11) by JdbcVehicleDestinationReader
// -- the vehicle's currently assigned destination, ready to feed into
// EtaCalculator once paired with the vehicle's current position.
public record VehicleDestination(UUID vehicleId, UUID organizationId, double lat, double lon) {
}

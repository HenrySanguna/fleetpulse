package dev.fleetpulse.processor.simulator;

// Task 5.3: one simulated vehicle's current position/motion. SimulatedVehicle
// holds exactly one instance of this record, reassigned every tick -- never
// a growing history -- which is what keeps per-vehicle memory footprint
// constant no matter how many telemetry messages have been published.
public record SimulatedVehicleState(
    double lat,
    double lon,
    double speedKmh,
    double heading,
    boolean ignition
) {
}

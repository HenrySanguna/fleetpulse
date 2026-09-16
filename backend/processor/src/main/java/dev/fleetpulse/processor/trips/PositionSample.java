package dev.fleetpulse.processor.trips;

import java.time.Instant;

// One row read back from `positions` for trip segmentation (task 1.2) --
// decoupled from TelemetryMessage (the live MQTT ingestion shape) because
// this is read from already-persisted history, not off the wire, and
// carries no heading field segmentation has no use for. ignition is still
// needed: TripSegmentationTask replays these samples through the existing
// VehicleMotionStreakTracker/MotionDetector (geo-core, change 01/03) to
// classify MOVING/IDLING/STOPPED exactly the same way the live path does,
// and MotionSample.engineOn() is what tells IDLING (engine on, not moving)
// apart from STOPPED (engine off).
public record PositionSample(Instant recordedAt, double lat, double lon, Double speedKmh, Boolean ignition) {
}

package dev.fleetpulse.api.fleet;

import java.time.Instant;

// Task 2.1 extension (gap: design.md/task 4.6 assume this endpoint exists --
// see tasks.md's Review Workload Forecast). One point of the simplified
// historical track; bare array response, matching design.md's httpResource
// example (`Position[]`), not wrapped.
public record TrackPointResponse(double lat, double lon, Instant recordedAt) {
}

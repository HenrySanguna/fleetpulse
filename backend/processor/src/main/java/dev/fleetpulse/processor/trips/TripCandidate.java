package dev.fleetpulse.processor.trips;

import java.time.Instant;
import java.util.UUID;

// One CLOSED trip produced by TripSegmenter (tasks 1.2/1.4) -- ready to be
// persisted as-is by JdbcTripWriter. "Closed" means a subsequent
// STOPPED/IDLING run at least as long as the organization's configured
// stop threshold was actually observed; the still-open trailing segment at
// the end of a processing window is never represented by this type (see
// TripSegmenter's own class comment).
public record TripCandidate(
    UUID vehicleId,
    UUID organizationId,
    Instant startedAt,
    Instant endedAt,
    double distanceKm,
    long durationSecs,
    long idleSecs,
    double maxSpeedKmh,
    double avgSpeedKmh
) {
}

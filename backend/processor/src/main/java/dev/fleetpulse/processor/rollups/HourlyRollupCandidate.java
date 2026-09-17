package dev.fleetpulse.processor.rollups;

import java.time.Instant;
import java.util.UUID;

// One recomputed vehicle_hourly row (tasks 4.1/5.4/5.5) -- ready to be
// persisted as-is by JdbcHourlyRollupWriter's ON CONFLICT (vehicle_id, hour)
// DO UPDATE. `hour` is always truncated to the exact hour boundary
// (HourlyRollupAggregator.truncateToHour). maxSpeedKmh mirrors
// vehicle_hourly's own nullable column: null when no position in this hour
// bucket reported a speed at all.
public record HourlyRollupCandidate(
    UUID vehicleId,
    UUID organizationId,
    Instant hour,
    double distanceKm,
    long movingSecs,
    long idleSecs,
    Double maxSpeedKmh
) {
}

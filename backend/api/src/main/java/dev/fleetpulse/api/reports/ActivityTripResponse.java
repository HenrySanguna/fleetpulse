package dev.fleetpulse.api.reports;

import java.time.Instant;
import java.util.UUID;

// Task 4.3: one row of the report's own trip list, read from `trips` (V10,
// WU1) -- the exact table task 1.1's own resolution note says was given a
// synthetic `id` specifically so this report could reference individual
// trips by a stable identifier. startedAt/endedAt are raw ISO-8601 instants,
// not the console's provisional mock date/startTime/endTime pre-formatted
// strings -- same "raw data in, format at the presentation layer" split
// DailyDistancePointResponse's own comment documents; durationMinutes/
// idleMinutes are still converted here (trips.duration_secs/idle_secs / 60)
// since that integer-minutes shape is what the console's own
// formatHoursMinutes() already expects and neither backend nor frontend
// gains anything from carrying raw seconds across the wire only to divide
// by 60 again on the other side. avg_speed_kmh (trips' own column) is
// deliberately NOT surfaced per-row here -- the report's summary already
// carries a range-wide average, and design.md/the mock UI never asked for a
// per-trip average speed column.
public record ActivityTripResponse(
    UUID id,
    Instant startedAt,
    Instant endedAt,
    double distanceKm,
    int durationMinutes,
    int idleMinutes,
    double maxSpeedKmh
) {
}

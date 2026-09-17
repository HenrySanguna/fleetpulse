package dev.fleetpulse.api.reports;

// Task 4.3: aggregated totals for the requested range, derived entirely from
// vehicle_daily rows (never vehicle_hourly, never positions -- see
// JdbcActivityReportRepository's own class comment for why vehicle_hourly is
// not needed here). movingMinutes/idleMinutes are already converted from
// vehicle_daily's own *_secs columns (integer division, matching the
// console's pre-existing formatHoursMinutes() input shape, so the frontend
// model needed no field renaming for this DTO). avgSpeedKmh is
// totalDistanceKm / ((movingSecs + idleSecs) / 3600.0), the same
// distance-over-accounted-time formula TripSegmenter.addTrip() already
// established for a single trip's own avg_speed_kmh, applied here across
// the whole range instead of one trip; 0.0 when the range has no accounted
// time at all (no daily rows, or a vehicle at a dead stop the entire
// period). maxSpeedKmh is nullable: a vehicle with zero daily rows in range
// (never reported during that window) has no observed speed to report.
public record ActivityReportSummaryResponse(
    double totalDistanceKm,
    int movingMinutes,
    int idleMinutes,
    double avgSpeedKmh,
    Double maxSpeedKmh
) {
}

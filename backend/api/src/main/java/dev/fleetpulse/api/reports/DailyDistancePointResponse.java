package dev.fleetpulse.api.reports;

import java.time.LocalDate;

// Task 4.4: one bar of the console's "Distancia por día" chart, read
// directly from a vehicle_daily row -- a rollup table is exactly what
// design.md's "Rollups en lugar de continuous aggregates" section built
// vehicle_daily for. `day` is the plain UTC calendar date (matching
// vehicle_daily.day's own definition, V13), not a pre-formatted Spanish
// weekday abbreviation ("Lun"/"Mar"/...) the way the console's provisional
// mock data used to return it -- ActivityReportPageComponent now derives
// that label itself (Intl-based, locale-correct for any range, not just a
// fixed 7-day Mon-Sun window), the same "backend returns raw ISO, the page
// component formats for display" split AlertsPageComponent's own
// formatTime()/dayLabel() already established for occurredAt.
public record DailyDistancePointResponse(
    LocalDate day,
    double distanceKm
) {
}

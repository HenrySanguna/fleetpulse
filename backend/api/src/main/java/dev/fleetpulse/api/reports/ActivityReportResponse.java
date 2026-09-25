package dev.fleetpulse.api.reports;

import java.util.List;
import java.util.UUID;

// Task 4.3: response DTO for GET /api/vehicles/{vehicleId}/activity-report.
// Shape resolved against the console's own pre-existing (mock-backed)
// ActivityReport model (apps/console/.../activity-report/models/
// activity-report.model.ts) rather than invented from scratch -- design.md
// only says "informe de actividad por vehículo y rango de fechas" with no
// schema of its own, so the already-built UI is the closest thing to a
// spec this task has. summary/dailyDistances/trips field names are carried
// over unchanged; only their per-field content shape moved from
// presentation-formatted strings to raw data (see
// DailyDistancePointResponse/ActivityTripResponse's own comments).
//
// Task 10: inProgressTrip is nullable -- present only when the vehicle is
// currently inside an unclosed trip AND the requested range reaches today
// (see ActivityReportService.computeInProgressTrip()); null for a past
// range or a vehicle that is not currently moving/recently stopped. Kept as
// its own field rather than folding it into `trips` (e.g. a nullable `id`
// on ActivityTripResponse) since it has no id/endedAt of its own -- a
// separate, smaller DTO (ActivityInProgressTripResponse) is the cleaner
// contract.
public record ActivityReportResponse(
    UUID vehicleId,
    ActivityReportSummaryResponse summary,
    List<DailyDistancePointResponse> dailyDistances,
    List<ActivityTripResponse> trips,
    ActivityInProgressTripResponse inProgressTrip
) {
}

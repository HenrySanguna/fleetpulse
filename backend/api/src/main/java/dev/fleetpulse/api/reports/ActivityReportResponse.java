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
public record ActivityReportResponse(
    UUID vehicleId,
    ActivityReportSummaryResponse summary,
    List<DailyDistancePointResponse> dailyDistances,
    List<ActivityTripResponse> trips
) {
}

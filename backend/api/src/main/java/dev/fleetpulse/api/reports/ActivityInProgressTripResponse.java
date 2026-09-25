package dev.fleetpulse.api.reports;

import java.time.Instant;

// Task 10 (prod QA: "el informe de actividad muestra el viaje en curso"):
// `trips` (ActivityTripResponse) only ever holds CLOSED trips --
// TripSegmenter (processor module) deliberately never emits the trailing
// span at the end of a processing window, see that class's own comment --
// while the summary (vehicle_daily, built straight from positions/motion
// state) already counts an ongoing trip's distance/moving time. This
// record is the display-only stand-in for that still-open trip, computed
// in InProgressTripCalculator rather than read from any table: no `id`
// (nothing is persisted yet) and no `endedAt` (it has not ended -- the
// console renders "ahora"/"En curso" instead of a real end time).
// distanceKm/durationMinutes/idleMinutes/maxSpeedKmh are computed the same
// way TripSegmenter.addTrip() computes them for a closed trip, over
// whatever positions fall inside the still-open span.
public record ActivityInProgressTripResponse(
    Instant startedAt,
    double distanceKm,
    int durationMinutes,
    int idleMinutes,
    double maxSpeedKmh
) {
}

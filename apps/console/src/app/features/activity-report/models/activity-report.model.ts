// A vehicle option for the filter bar's selector. Kept separate from
// live-map's VehicleState -- this feature reads its own small real snapshot
// (GET /api/fleet/state via ActivityReportService.listVehicles(), never
// through live-map's own FleetStore, see ActivityReportService's doc
// comment for why), so it only needs the two fields the selector actually
// renders.
export interface ActivityVehicleOption {
  readonly id: string;
  readonly label: string;
}

// Task 4.3 (real backend wiring): durations stay whole minutes (not a
// {hours, minutes} shape) -- simplest representation that stays consistent
// between the summary and each trip row, formatted to "Xh Ym" only at the
// presentation layer (the page component), same split AlertsPageComponent
// uses for its own occurredAt -> display-string formatting. The backend
// (ActivityReportSummaryResponse) already converts seconds to minutes, so
// this shape needed no change from the provisional mock version.
export interface ActivityReportSummary {
  readonly totalDistanceKm: number;
  readonly movingMinutes: number;
  readonly idleMinutes: number;
  readonly avgSpeedKmh: number;
  readonly maxSpeedKmh: number;
}

// `day` changed from the mock's pre-formatted Spanish weekday abbreviation
// ("Lun") to a raw ISO calendar date (backend: DailyDistancePointResponse.day,
// vehicle_daily's own UTC day column) -- ActivityReportPageComponent now
// derives the weekday label itself (locale-correct for any date, not just a
// fixed Mon-Sun mock week), the same "raw data in, format at the
// presentation layer" split AlertsPageComponent's occurredAt already
// established.
export interface DailyDistancePoint {
  readonly day: string;
  readonly distanceKm: number;
}

// `date`/`startTime`/`endTime` (pre-formatted mock strings) replaced by
// `id`/`startedAt`/`endedAt` (the real trips row's own stable id plus raw
// ISO-8601 instants, backend: ActivityTripResponse) -- same presentation
// split as DailyDistancePoint.day above; ActivityReportPageComponent formats
// both the date and the start/end time from these two instants.
export interface ActivityTripRow {
  readonly id: string;
  readonly startedAt: string;
  readonly endedAt: string;
  readonly distanceKm: number;
  readonly durationMinutes: number;
  readonly idleMinutes: number;
  readonly maxSpeedKmh: number;
}

export interface ActivityReport {
  readonly vehicleId: string;
  readonly summary: ActivityReportSummary;
  readonly dailyDistances: readonly DailyDistancePoint[];
  readonly trips: readonly ActivityTripRow[];
}

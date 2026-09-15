// A vehicle option for the filter bar's selector. Kept separate from
// live-map's VehicleState -- this feature falls back to its own small mock
// vehicle list (see ActivityReportService's doc comment for why), so it
// only needs the two fields the selector actually renders.
export interface ActivityVehicleOption {
  readonly id: string;
  readonly label: string;
}

// Durations as whole minutes (not a {hours, minutes} shape) -- simplest
// representation that stays consistent between the summary and each trip
// row, formatted to "Xh Ym" only at the presentation layer (the page
// component), same split AlertsPageComponent uses for its own
// occurredAt -> display-string formatting.
export interface ActivityReportSummary {
  readonly totalDistanceKm: number;
  readonly movingMinutes: number;
  readonly idleMinutes: number;
  readonly avgSpeedKmh: number;
  readonly maxSpeedKmh: number;
}

export interface DailyDistancePoint {
  readonly day: string;
  readonly distanceKm: number;
}

export interface ActivityTripRow {
  readonly date: string;
  readonly startTime: string;
  readonly endTime: string;
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

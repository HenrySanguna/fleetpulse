import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import type { Observable } from 'rxjs';
import { map } from 'rxjs';
import {
  ActivityReportControllerService,
  FleetStateControllerService,
  type ActivityInProgressTripResponse,
  type ActivityReportResponse,
  type ActivityTripResponse,
  type DailyDistancePointResponse,
  type FleetStateResponse,
  type VehicleStateResponse,
} from '@fleetpulse/api-client';
import { getJson } from '../../../core/http/api-client-json-get';
import type {
  ActivityInProgressTrip,
  ActivityReport,
  ActivityTripRow,
  ActivityVehicleOption,
  DailyDistancePoint,
} from '../models/activity-report.model';

// Task 4.3 (real backend wiring, replacing the provisional MOCK_REPORTS this
// file previously fabricated -- see the launch prompt's own note that this
// screen predates its backend, same era as AlertsService before task 3.4).
// `ActivityReportControllerService`/`FleetStateControllerService` (generated)
// are only ever injected to read their already-pinned `configuration`
// (basePath/withCredentials) -- the same getJson Accept-header workaround
// GeofenceService/VehicleTrackService/AlertsService already established
// (see core/http/api-client-json-get.ts's own comment).
//
// listVehicles() now reads the real GET /api/fleet/state snapshot instead of
// a fixed mock list -- necessary, not just an upgrade: the mock's own fake
// ids ("VH-1042") never exist in the real `vehicles` table, so a getReport()
// call built against them would 404 against the real endpoint every time.
// GET /api/fleet/state is a plain HTTP snapshot read (FleetStateService,
// JPA+JDBC, task 2.1) with no MQTT dependency of its own -- calling it
// directly here still avoids the ONE thing this file's original mock
// comment actually warned against (forcing live-map's own FleetStore/
// FleetStartupService MQTT connection open just to fill a dropdown); it
// only reuses the already-existing REST snapshot, the same "HTTP first,
// live stream takes over" shape design.md establishes for the live map
// itself, minus the live-stream half this screen has no use for.
function requiredVehicle<T>(value: T | undefined, field: string): T {
  if (value === undefined) {
    throw new Error(`ActivityReportService: fleet state response missing required field "${field}"`);
  }
  return value;
}

function toVehicleOption(vehicle: VehicleStateResponse): ActivityVehicleOption {
  return {
    id: requiredVehicle(vehicle.vehicleId, 'vehicleId'),
    label: requiredVehicle(vehicle.label, 'label'),
  };
}

// Every field on the generated response DTOs is typed optional -- the same
// no-`required`-array OpenAPI gap AlertsService's own comment already
// documents for AlertResponse, not something specific to this endpoint.
// ActivityReportController/JdbcActivityReportRepository always populate
// every one of these columns, so a genuinely undefined value here means a
// malformed response worth failing loudly on.
function required<T>(value: T | undefined, field: string): T {
  if (value === undefined) {
    throw new Error(`ActivityReportService: response missing required field "${field}"`);
  }
  return value;
}

function toDailyPoint(point: DailyDistancePointResponse): DailyDistancePoint {
  return {
    day: required(point.day, 'dailyDistances[].day'),
    distanceKm: required(point.distanceKm, 'dailyDistances[].distanceKm'),
  };
}

function toTripRow(trip: ActivityTripResponse): ActivityTripRow {
  return {
    id: required(trip.id, 'trips[].id'),
    startedAt: required(trip.startedAt, 'trips[].startedAt'),
    endedAt: required(trip.endedAt, 'trips[].endedAt'),
    distanceKm: required(trip.distanceKm, 'trips[].distanceKm'),
    durationMinutes: required(trip.durationMinutes, 'trips[].durationMinutes'),
    idleMinutes: required(trip.idleMinutes, 'trips[].idleMinutes'),
    maxSpeedKmh: required(trip.maxSpeedKmh, 'trips[].maxSpeedKmh'),
  };
}

// Task 10: unlike every other field here, inProgressTrip is genuinely
// optional on the wire (ActivityReportResponse.inProgressTrip, null for a
// past range or a vehicle that is not currently in a trip) -- so this maps
// it only when present rather than failing loudly on a missing field.
function toInProgressTrip(trip: ActivityInProgressTripResponse | undefined): ActivityInProgressTrip | undefined {
  if (!trip) {
    return undefined;
  }
  return {
    startedAt: required(trip.startedAt, 'inProgressTrip.startedAt'),
    distanceKm: required(trip.distanceKm, 'inProgressTrip.distanceKm'),
    durationMinutes: required(trip.durationMinutes, 'inProgressTrip.durationMinutes'),
    idleMinutes: required(trip.idleMinutes, 'inProgressTrip.idleMinutes'),
    maxSpeedKmh: required(trip.maxSpeedKmh, 'inProgressTrip.maxSpeedKmh'),
  };
}

function toReport(response: ActivityReportResponse): ActivityReport {
  const summary = required(response.summary, 'summary');
  return {
    vehicleId: required(response.vehicleId, 'vehicleId'),
    summary: {
      totalDistanceKm: required(summary.totalDistanceKm, 'summary.totalDistanceKm'),
      movingMinutes: required(summary.movingMinutes, 'summary.movingMinutes'),
      idleMinutes: required(summary.idleMinutes, 'summary.idleMinutes'),
      avgSpeedKmh: required(summary.avgSpeedKmh, 'summary.avgSpeedKmh'),
      // maxSpeedKmh is the one summary field the backend can genuinely send
      // as null (ActivityReportSummaryResponse.maxSpeedKmh, a vehicle with
      // zero vehicle_daily rows in range) rather than merely optional --
      // coalesced to 0, matching every other "nothing to report" field in
      // this same summary.
      maxSpeedKmh: summary.maxSpeedKmh ?? 0,
    },
    dailyDistances: (response.dailyDistances ?? []).map(toDailyPoint),
    trips: (response.trips ?? []).map(toTripRow),
    inProgressTrip: toInProgressTrip(response.inProgressTrip),
  };
}

@Injectable({ providedIn: 'root' })
export class ActivityReportService {
  private readonly http = inject(HttpClient);
  private readonly api = inject(ActivityReportControllerService);
  private readonly fleetApi = inject(FleetStateControllerService);

  listVehicles(): Observable<ActivityVehicleOption[]> {
    return getJson<FleetStateResponse>(this.http, this.fleetApi.configuration, '/api/fleet/state').pipe(
      map((response) => (response.vehicles ?? []).map(toVehicleOption)),
    );
  }

  getReport(vehicleId: string, from: string, to: string): Observable<ActivityReport> {
    const query = new URLSearchParams({ from, to }).toString();
    return getJson<ActivityReportResponse>(this.http, this.api.configuration, `/api/vehicles/${vehicleId}/activity-report?${query}`).pipe(
      map(toReport),
    );
  }
}

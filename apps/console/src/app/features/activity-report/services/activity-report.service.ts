import { Injectable } from '@angular/core';
import { type Observable, delay, of } from 'rxjs';
import type { ActivityReport, ActivityVehicleOption } from '../models/activity-report.model';

// PROVISIONAL MOCK DATA. The trip/activity-report backend (rollups, per-trip
// metrics) is proposed but not yet speced/designed/implemented -- see
// openspec/changes/06-add-trips-eta-alerts/proposal.md ("Métricas por
// viaje" / "Informe de actividad por vehículo y rango de fechas"). No
// backend endpoint exists yet, so `getReport()` fabricates a realistic
// dataset per vehicle instead of calling the API. Once that change lands,
// only this service's method bodies need to change (to HttpClient calls),
// same "swap the service, keep the store/page" split AlertsService/
// AlertsStore already established -- ActivityReportStore and
// ActivityReportPageComponent stay exactly as they are.
//
// `listVehicles()` is a *separate*, smaller mock: FleetStore (live-map's
// real vehicle registry) is only populated once FleetStartupService.start()
// runs, and that only happens from LiveMapPageComponent's constructor --
// navigating straight to /activity never triggers it, so FleetStore would
// be empty here. Rather than force a live MQTT connection just to fill a
// dropdown, this keeps its own tiny fixed vehicle list; only the trip
// metrics below are meant to be mocked long-term.
const MOCK_VEHICLES: ActivityVehicleOption[] = [
  { id: 'VH-1042', label: 'Camión 04' },
  { id: 'VH-0892', label: 'Furgoneta 02' },
  { id: 'VH-1107', label: 'Camión 11' },
];

const MOCK_REPORTS: Record<string, ActivityReport> = {
  'VH-1042': {
    vehicleId: 'VH-1042',
    summary: { totalDistanceKm: 428.6, movingMinutes: 684, idleMinutes: 112, avgSpeedKmh: 54, maxSpeedKmh: 97 },
    dailyDistances: [
      { day: 'Lun', distanceKm: 52.0 },
      { day: 'Mar', distanceKm: 78.0 },
      { day: 'Mié', distanceKm: 0 },
      { day: 'Jue', distanceKm: 91.3 },
      { day: 'Vie', distanceKm: 72.0 },
      { day: 'Sáb', distanceKm: 88.4 },
      { day: 'Dom', distanceKm: 46.9 },
    ],
    trips: [
      { date: '14/09', startTime: '07:02', endTime: '09:18', distanceKm: 61.2, durationMinutes: 136, idleMinutes: 8, maxSpeedKmh: 94 },
      { date: '13/09', startTime: '06:45', endTime: '12:30', distanceKm: 88.4, durationMinutes: 190, idleMinutes: 31, maxSpeedKmh: 97 },
      { date: '12/09', startTime: '07:15', endTime: '10:02', distanceKm: 64.0, durationMinutes: 167, idleMinutes: 22, maxSpeedKmh: 88 },
      { date: '10/09', startTime: '06:50', endTime: '11:40', distanceKm: 91.3, durationMinutes: 170, idleMinutes: 19, maxSpeedKmh: 92 },
    ],
  },
  'VH-0892': {
    vehicleId: 'VH-0892',
    summary: { totalDistanceKm: 156.4, movingMinutes: 402, idleMinutes: 168, avgSpeedKmh: 33, maxSpeedKmh: 71 },
    dailyDistances: [
      { day: 'Lun', distanceKm: 18.2 },
      { day: 'Mar', distanceKm: 24.6 },
      { day: 'Mié', distanceKm: 21.0 },
      { day: 'Jue', distanceKm: 0 },
      { day: 'Vie', distanceKm: 32.8 },
      { day: 'Sáb', distanceKm: 29.4 },
      { day: 'Dom', distanceKm: 30.4 },
    ],
    trips: [
      { date: '14/09', startTime: '08:10', endTime: '09:40', distanceKm: 14.6, durationMinutes: 90, idleMinutes: 22, maxSpeedKmh: 58 },
      { date: '13/09', startTime: '08:05', endTime: '11:20', distanceKm: 24.6, durationMinutes: 195, idleMinutes: 48, maxSpeedKmh: 64 },
      { date: '12/09', startTime: '08:15', endTime: '10:00', distanceKm: 21.0, durationMinutes: 105, idleMinutes: 30, maxSpeedKmh: 52 },
      { date: '10/09', startTime: '08:00', endTime: '12:10', distanceKm: 32.8, durationMinutes: 250, idleMinutes: 68, maxSpeedKmh: 71 },
    ],
  },
  'VH-1107': {
    vehicleId: 'VH-1107',
    summary: { totalDistanceKm: 612.9, movingMinutes: 780, idleMinutes: 205, avgSpeedKmh: 47, maxSpeedKmh: 103 },
    dailyDistances: [
      { day: 'Lun', distanceKm: 95.4 },
      { day: 'Mar', distanceKm: 0 },
      { day: 'Mié', distanceKm: 112.6 },
      { day: 'Jue', distanceKm: 88.0 },
      { day: 'Vie', distanceKm: 101.2 },
      { day: 'Sáb', distanceKm: 120.7 },
      { day: 'Dom', distanceKm: 95.0 },
    ],
    trips: [
      { date: '14/09', startTime: '05:30', endTime: '09:45', distanceKm: 95.4, durationMinutes: 255, idleMinutes: 35, maxSpeedKmh: 98 },
      { date: '13/09', startTime: '05:45', endTime: '11:10', distanceKm: 112.6, durationMinutes: 325, idleMinutes: 52, maxSpeedKmh: 103 },
      { date: '12/09', startTime: '06:00', endTime: '10:20', distanceKm: 88.0, durationMinutes: 260, idleMinutes: 41, maxSpeedKmh: 91 },
      { date: '10/09', startTime: '05:50', endTime: '11:35', distanceKm: 101.2, durationMinutes: 345, idleMinutes: 44, maxSpeedKmh: 97 },
    ],
  },
};

@Injectable({ providedIn: 'root' })
export class ActivityReportService {
  listVehicles(): Observable<ActivityVehicleOption[]> {
    return of(MOCK_VEHICLES).pipe(delay(150));
  }

  getReport(vehicleId: string): Observable<ActivityReport> {
    const report = MOCK_REPORTS[vehicleId] ?? MOCK_REPORTS[MOCK_VEHICLES[0].id];
    return of(report).pipe(delay(300));
  }
}

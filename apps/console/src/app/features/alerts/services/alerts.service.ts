import { Injectable } from '@angular/core';
import { type Observable, delay, of } from 'rxjs';
import type { Alert } from '../models/alert.model';

// PROVISIONAL MOCK DATA. Alerting (geofence/speeding/idle/offline detection)
// is proposed but not yet speced/designed/implemented -- see
// openspec/changes/06-add-trips-eta-alerts/proposal.md. No backend endpoint
// exists yet, so `list()` fabricates a realistic set instead of calling the
// API. Once that change lands, only this method's body needs to change (to
// an HttpClient call, same "swap the service, keep the store/page" split
// GeofenceService/GeofenceStore already established) -- AlertsStore and
// AlertsPageComponent stay exactly as they are.
//
// Timestamps are computed relative to "now" (rather than hardcoded) so the
// list always has a plausible "Hoy"/"Ayer" spread, whenever this runs.
function todayAt(hours: number, minutes: number): string {
  const date = new Date();
  date.setHours(hours, minutes, 0, 0);
  return date.toISOString();
}

function yesterdayAt(hours: number, minutes: number): string {
  const date = new Date();
  date.setDate(date.getDate() - 1);
  date.setHours(hours, minutes, 0, 0);
  return date.toISOString();
}

const MOCK_ALERTS: Alert[] = [
  {
    id: 'a1',
    vehicleId: 'VH-1042',
    vehicleLabel: 'Camión 04',
    type: 'geofence_enter',
    detail: 'Entró en la geocerca "Puerto de Valencia"',
    occurredAt: todayAt(10, 42),
    acknowledged: false,
  },
  {
    id: 'a2',
    vehicleId: 'VH-0892',
    vehicleLabel: 'Furgoneta 02',
    type: 'speeding',
    detail: '92 km/h en una zona con límite de 60 km/h',
    occurredAt: todayAt(10, 31),
    acknowledged: false,
  },
  {
    id: 'a3',
    vehicleId: 'VH-1107',
    vehicleLabel: 'Camión 11',
    type: 'excessive_idle',
    detail: '22 min detenido con el motor en marcha',
    occurredAt: todayAt(9, 58),
    acknowledged: true,
  },
  {
    id: 'a4',
    vehicleId: 'VH-1070',
    vehicleLabel: 'Camión 07',
    type: 'offline',
    detail: 'Sin señal del dispositivo desde hace 34 min',
    occurredAt: todayAt(9, 40),
    acknowledged: false,
  },
  {
    id: 'a5',
    vehicleId: 'VH-0905',
    vehicleLabel: 'Furgoneta 05',
    type: 'geofence_exit',
    detail: 'Salió de la geocerca "Depósito Norte"',
    occurredAt: yesterdayAt(8, 15),
    acknowledged: true,
  },
  {
    id: 'a6',
    vehicleId: 'VH-1058',
    vehicleLabel: 'Camión 09',
    type: 'offline',
    detail: 'Sin señal del dispositivo desde hace 2 h',
    occurredAt: yesterdayAt(7, 20),
    acknowledged: true,
  },
];

@Injectable({ providedIn: 'root' })
export class AlertsService {
  list(): Observable<Alert[]> {
    return of(MOCK_ALERTS).pipe(delay(300));
  }
}

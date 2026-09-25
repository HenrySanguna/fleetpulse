import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import type { Observable } from 'rxjs';
import { map } from 'rxjs';
import { AlertsControllerService, type AlertResponse } from '@fleetpulse/api-client';
import { getJson, patchJson } from '../../../core/http/api-client-json-get';
import type { Alert, AlertType } from '../models/alert.model';

// Task 3.4: real backend wiring for GET /api/alerts and PATCH
// /api/alerts/{id}/acknowledge, replacing the provisional MOCK_ALERTS this
// file previously fabricated (06-add-trips-eta-alerts's own proposal.md
// noted the backend did not exist yet). `AlertsControllerService` (generated)
// is only ever injected to read its already-pinned `configuration`
// (basePath/withCredentials) -- the same getJson/patchJson Accept-header
// workaround GeofenceService/VehicleTrackService already established (see
// core/http/api-client-json-get.ts's own comment).
//
// AlertResponse (backend DTO) has no free-text `detail` field -- `alerts`
// (V12) only persists alert_type/context, never a measurement like "92 km/h"
// or "22 min detenido" the mock data used to show. Documented deviation
// (tasks.md's own resolution note): detail is synthesized client-side from
// alertType (+ contextLabel for geofence_* types) instead, the same
// "derive a human label from real data" shape formatEtaLabel() already
// established for ETA (libs/console-ui).
const DETAIL_BY_TYPE: Record<AlertType, (contextLabel: string | null) => string> = {
  geofence_enter: (label) => (label ? `Entró en la geocerca "${label}"` : 'Entró en una geocerca'),
  geofence_exit: (label) => (label ? `Salió de la geocerca "${label}"` : 'Salió de una geocerca'),
  geofence_dwell: (label) => (label ? `Permanece en la geocerca "${label}"` : 'Permanece en una geocerca'),
  speeding: () => 'Exceso de velocidad detectado',
  excessive_idle: () => 'Ralentí excesivo detectado',
  offline: () => 'Sin señal del dispositivo',
};

// Every field on the generated AlertResponse is typed optional -- the
// OpenAPI spec backend/api publishes carries no `required` array for any
// response schema in this codebase (GeofenceResponse/VehicleDestinationResponse
// have the exact same all-optional shape), not something specific to alerts.
// AlertsController/JdbcAlertsRepository always populate every one of these
// columns (alerts' own NOT NULL constraints back id/vehicle_id/alert_type/
// occurred_at/acknowledged; vehicleLabel comes from an INNER JOIN on
// vehicles, never left-joined), so a genuinely undefined value here means a
// malformed response worth failing loudly on, not a case to silently
// coalesce -- keeping Alert's own fields non-optional instead of weakening
// them to match the generated type.
function required<T>(value: T | undefined, field: string): T {
  if (value === undefined) {
    throw new Error(`AlertsService: response missing required field "${field}"`);
  }
  return value;
}

function toAlert(response: AlertResponse): Alert {
  const type = required(response.alertType, 'alertType') as AlertType;
  return {
    id: required(response.id, 'id'),
    vehicleId: required(response.vehicleId, 'vehicleId'),
    vehicleLabel: required(response.vehicleLabel, 'vehicleLabel'),
    type,
    detail: DETAIL_BY_TYPE[type](response.contextLabel ?? null),
    occurredAt: required(response.occurredAt, 'occurredAt'),
    acknowledged: required(response.acknowledged, 'acknowledged'),
    // Same "absent means genuinely null" reasoning as contextLabel above --
    // both are null until (and unless) an alert is acknowledged, not a
    // malformed-response case worth failing on like the required() fields.
    acknowledgedAt: response.acknowledgedAt ?? null,
    acknowledgedBy: response.acknowledgedBy ?? null,
  };
}

@Injectable({ providedIn: 'root' })
export class AlertsService {
  private readonly http = inject(HttpClient);
  private readonly api = inject(AlertsControllerService);

  list(): Observable<Alert[]> {
    return getJson<AlertResponse[]>(this.http, this.api.configuration, '/api/alerts').pipe(
      map((responses) => responses.map(toAlert)),
    );
  }

  acknowledge(id: string): Observable<Alert> {
    return patchJson<AlertResponse>(this.http, this.api.configuration, `/api/alerts/${id}/acknowledge`).pipe(map(toAlert));
  }
}

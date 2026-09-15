import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import type { Observable } from 'rxjs';
import { tap } from 'rxjs';
import { GeofenceControllerService, type GeofenceRequest, type GeofenceResponse } from '@fleetpulse/api-client';
import { getJson, postJson, putJson } from '../../../core/http/api-client-json-get';
import { GeofenceStore } from './geofence.store';

// Tasks 5.1-5.3: HTTP orchestration for the existing CRUD backend (WU6).
// `GeofenceControllerService` (generated) is only ever injected to read its
// already-pinned `configuration` (basePath/withCredentials) -- the same
// workaround FleetStartupService/VehicleTrackService already established for
// the generated client's Accept-header-defaults-to-blob gap (see
// core/http/api-client-json-get.ts), confirmed to affect create()/update()
// too while wiring this service.
@Injectable({ providedIn: 'root' })
export class GeofenceService {
  private readonly http = inject(HttpClient);
  private readonly api = inject(GeofenceControllerService);
  private readonly store = inject(GeofenceStore);

  load(): void {
    this.store.setLoading(true);
    getJson<GeofenceResponse[]>(this.http, this.api.configuration, '/api/geofences').subscribe({
      next: (geofences) => this.store.setGeofences(geofences),
      error: (error: unknown) => {
        console.error('GeofenceService: failed to load geofences', error);
        this.store.setError('Failed to load geofences');
      },
    });
  }

  create(request: GeofenceRequest): Observable<GeofenceResponse> {
    return postJson<GeofenceResponse>(this.http, this.api.configuration, '/api/geofences', request).pipe(
      tap((response) => this.store.upsertGeofence(response)),
    );
  }

  update(id: string, request: GeofenceRequest): Observable<GeofenceResponse> {
    return putJson<GeofenceResponse>(this.http, this.api.configuration, `/api/geofences/${id}`, request).pipe(
      tap((response) => this.store.upsertGeofence(response)),
    );
  }
}

import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import type { Observable } from 'rxjs';
import { DispatcherSessionControllerService, type DispatcherSelfView } from '@fleetpulse/api-client';
import { getJson, postForm } from '../http/api-client-json-get';

// `DispatcherSessionControllerService` is only ever injected to read its
// already-pinned `configuration` (basePath/withCredentials) -- same
// workaround established by FleetStartupService/GeofenceService (see
// core/http/api-client-json-get.ts). `/login` and `/logout` have no
// generated client method at all (Spring Security defaults, not part of the
// OpenAPI contract), so this reuses that configuration purely as "the API's
// basePath/withCredentials", not as a per-endpoint client.
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);
  private readonly api = inject(DispatcherSessionControllerService);

  login(email: string, password: string): Observable<void> {
    const body = new URLSearchParams();
    body.set('username', email);
    body.set('password', password);
    return postForm(this.http, this.api.configuration, '/login', body);
  }

  me(): Observable<DispatcherSelfView> {
    return getJson<DispatcherSelfView>(this.http, this.api.configuration, '/api/dispatchers/me');
  }

  logout(): Observable<void> {
    return postForm(this.http, this.api.configuration, '/logout', new URLSearchParams());
  }
}

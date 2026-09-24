import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import type { Observable } from 'rxjs';
import { finalize, of, shareReplay, tap } from 'rxjs';
import { DispatcherSessionControllerService } from '@fleetpulse/api-client';
import { getJson } from '../http/api-client-json-get';

export interface CsrfToken {
  readonly headerName: string;
  readonly token: string;
}

// cross-site-csrf-token: the console cannot read the API's CSRF token off a
// cookie (SecurityConfig/CsrfTokenController -- different sites, and the
// token is now session-bound server-side, not cookie-issued at all), so this
// is the in-memory stand-in for what Angular's built-in XSRF interceptor
// would otherwise cache in `document.cookie`. Never persisted to local/
// session storage (constraint in odd/tasks/cross-site-csrf-token.md): a
// page reload always re-fetches, which is also correct given the token
// rotates server-side on every login.
//
// `DispatcherSessionControllerService` is injected only to read its already-
// pinned `configuration` (basePath/withCredentials) -- same workaround
// AuthService/VehicleTrackService already establish (see
// core/http/api-client-json-get.ts).
@Injectable({ providedIn: 'root' })
export class CsrfTokenService {
  private readonly http = inject(HttpClient);
  private readonly api = inject(DispatcherSessionControllerService);

  private cached: CsrfToken | null = null;
  private inFlight: Observable<CsrfToken> | null = null;

  // Concurrent callers share the same in-flight GET instead of each firing
  // their own: the first call populates `inFlight`, every call that lands
  // before it resolves gets that same shared, multicast observable back.
  token(): Observable<CsrfToken> {
    if (this.cached) {
      return of(this.cached);
    }
    if (!this.inFlight) {
      this.inFlight = getJson<CsrfToken>(this.http, this.api.configuration, '/api/csrf').pipe(
        tap((token) => (this.cached = token)),
        finalize(() => (this.inFlight = null)),
        shareReplay(1),
      );
    }
    return this.inFlight;
  }

  // Called after login (the server rotates the session's CSRF token on
  // authentication -- a pre-login token is stale the instant /login
  // succeeds) and after logout (the session, and the token bound to it,
  // stop existing).
  clear(): void {
    this.cached = null;
    this.inFlight = null;
  }
}

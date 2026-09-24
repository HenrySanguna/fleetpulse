import { Injectable } from '@angular/core';
import { Observable, catchError, of, shareReplay, timeout } from 'rxjs';

export interface GeolocationPoint {
  readonly lat: number;
  readonly lon: number;
}

const GEOLOCATION_TIMEOUT_MS = 8000;

// Wraps `navigator.geolocation` behind an injectable service (project
// convention: native/browser APIs never called directly from a component) so
// both LiveMapComponent and GeofenceDrawingEditorComponent can ask for the
// dispatcher's current position without duplicating permission handling,
// the SSR/test-environment guard, or the "ask only once per session" caching
// below -- two maps mounting in the same session must never trigger the
// browser permission prompt twice.
@Injectable({ providedIn: 'root' })
export class GeolocationService {
  private cached$: Observable<GeolocationPoint | undefined> | undefined;

  // Resolves to the dispatcher's current position, or `undefined` when
  // geolocation is unavailable (SSR/an older browser/this test environment),
  // denied, or doesn't resolve within GEOLOCATION_TIMEOUT_MS -- this never
  // errors, callers decide their own fallback view.
  position(): Observable<GeolocationPoint | undefined> {
    this.cached$ ??= this.requestPosition().pipe(shareReplay(1));
    return this.cached$;
  }

  private requestPosition(): Observable<GeolocationPoint | undefined> {
    const geolocation = typeof navigator === 'undefined' ? undefined : navigator.geolocation;
    if (!geolocation) {
      return of(undefined);
    }
    return new Observable<GeolocationPoint | undefined>((subscriber) => {
      geolocation.getCurrentPosition(
        (position) => {
          subscriber.next({ lat: position.coords.latitude, lon: position.coords.longitude });
          subscriber.complete();
        },
        () => {
          subscriber.next(undefined);
          subscriber.complete();
        },
        { timeout: GEOLOCATION_TIMEOUT_MS },
      );
    }).pipe(
      // Belt-and-suspenders: `getCurrentPosition`'s own `timeout` option is
      // browser-enforced and should always fire first, but this guarantees
      // callers never wait forever if a browser implementation (or a test
      // double) never invokes either callback.
      timeout(GEOLOCATION_TIMEOUT_MS + 1000),
      catchError(() => of(undefined)),
    );
  }
}

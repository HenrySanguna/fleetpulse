import type { HttpClient } from '@angular/common/http';
import type { Observable } from 'rxjs';
import { map } from 'rxjs';
import type { Configuration } from '@fleetpulse/api-client';

// Gap discovered in WU6 while writing E2E tests 6.5/6.6: every
// libs/api-client generated service method defaults its Accept header to
// the literal '*/*' whenever the OpenAPI spec does not restrict a response
// to one specific content type (Configuration.selectHeaderAccept /
// isJsonMime, libs/api-client/src/configuration.ts) -- '*/*' fails the
// generator's own isJsonMime() regex, so the generated method silently
// requests `responseType: 'blob'` instead of 'json'. The real backend still
// replies with an actual JSON body regardless (Spring's default Jackson
// converter does not care what Accept the client sent), so this was never
// visible as an HTTP error: every caller just silently received a Blob
// typed as its expected DTO, with every property reading back `undefined`.
// This broke FleetStartupService.start() (task 2.2) completely silently --
// `dispatcher.organizationId` was always undefined, so
// MqttConnectionService.connect() was never actually called in the real
// running app -- caught only once WU6's E2E tests finally exercised the
// real HttpClient request/response path, something no WU1-WU5 unit test
// could reach (they all mock the generated api-client services directly at
// injection, never the underlying HttpClient request).
//
// An HttpInterceptor cannot fix this: HttpClient.request()'s own body
// extraction runs a `switch (req.responseType)` against the ORIGINAL,
// caller-constructed request object AFTER the entire interceptor chain
// completes (see @angular/common's _module-chunk.mjs), throwing
// `NG02807: Response is not a Blob` the instant an interceptor produces (or
// lets through) a non-Blob body for a request that still declares
// `responseType: 'blob'` -- rewriting either the request or the response
// inside an interceptor hits the exact same check. Fixing libs/api-client's
// generated code is not an option either: its TS types literally lock
// `httpHeaderAccept` to the single '*/*' literal at each affected call
// site, and fixing the backend's OpenAPI annotations would ripple into
// WU1-WU5's already-open, unmerged PR diffs.
//
// The only real fix is to never let the generated method construct that
// broken request in the first place. `VehicleTrackService` (WU4,
// task 3.3) already established the pattern this reuses: read
// `basePath`/`withCredentials` off the already-injected generated service's
// own `configuration` (so this request always matches every other
// libs/api-client call -- one source of truth) and issue a plain
// `HttpClient.get<T>()` instead, whose default `responseType` is the
// correct 'json'.
export function getJson<T>(http: HttpClient, configuration: Configuration, path: string): Observable<T> {
  return http.get<T>(`${configuration.basePath}${path}`, { withCredentials: configuration.withCredentials });
}

// Same gap as getJson() above, confirmed again while wiring WU7's
// GeofenceService: GeofenceControllerService.create()/update() (generated)
// also default their Accept header to '*/*', so POST/PUT hit the identical
// responseType:'blob' misclassification -- not just the GET methods the
// original comment called out. Bypasses the generated method the same way.
export function postJson<T>(http: HttpClient, configuration: Configuration, path: string, body: unknown): Observable<T> {
  return http.post<T>(`${configuration.basePath}${path}`, body, { withCredentials: configuration.withCredentials });
}

export function putJson<T>(http: HttpClient, configuration: Configuration, path: string, body: unknown): Observable<T> {
  return http.put<T>(`${configuration.basePath}${path}`, body, { withCredentials: configuration.withCredentials });
}

// `/login` and `/logout` (Task 5.4/auth) aren't part of the OpenAPI-generated
// surface at all -- they're Spring Security's own default endpoints, not
// backed by a generated *ControllerService -- so this isn't the Accept-header
// workaround above, it's a different gap: both take a form-urlencoded body
// (not JSON) and reply with an empty 200 body, which HttpClient's default
// `responseType: 'json'` would throw a parse error on. `responseType: 'text'`
// avoids that; the resolved text (always empty on success) is discarded.
export function postForm(http: HttpClient, configuration: Configuration, path: string, body: URLSearchParams): Observable<void> {
  return http
    .post(`${configuration.basePath}${path}`, body, { withCredentials: configuration.withCredentials, responseType: 'text' })
    .pipe(map(() => undefined));
}

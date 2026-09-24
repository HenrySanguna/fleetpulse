import { inject } from '@angular/core';
import type { HttpEvent, HttpHandlerFn, HttpInterceptorFn, HttpRequest } from '@angular/common/http';
import { HttpErrorResponse } from '@angular/common/http';
import type { Observable } from 'rxjs';
import { catchError, switchMap, tap, throwError } from 'rxjs';
import { DispatcherSessionControllerService } from '@fleetpulse/api-client';
import { CsrfTokenService, type CsrfToken } from './csrf-token.service';

const UNSAFE_METHODS = new Set(['POST', 'PUT', 'PATCH', 'DELETE']);

// Compares parsed origins, not string prefixes: `${baseUrl}.evil.com` or
// `${baseUrl}@evil.com` must never receive the token.
function apiPath(url: string, baseUrl: string): string | undefined {
  let target: URL;
  let base: URL;
  try {
    target = new URL(url);
    base = new URL(baseUrl);
  } catch {
    return undefined;
  }
  if (target.origin !== base.origin) {
    return undefined;
  }
  const basePath = base.pathname.replace(/\/$/, '');
  if (basePath && target.pathname !== basePath && !target.pathname.startsWith(`${basePath}/`)) {
    return undefined;
  }
  return target.pathname.slice(basePath.length);
}

// cross-site-csrf-token: attaches the CSRF header (synchronizer token
// pattern, SecurityConfig/CsrfTokenController -- header name read from that
// endpoint's response, never hardcoded) to every unsafe request the console
// sends to the API. Angular's own built-in XSRF interceptor never applies
// here to begin with -- see app.config.ts's withNoXsrfProtection(), added to
// make that explicit rather than relying on it silently no-oping cross-site.
//
// /login is excluded on purpose, not just because SecurityConfig ignores it
// for CSRF: fetching a token requires an authenticated session
// (CsrfTokenController), which does not exist yet at that point -- trying
// would only add a doomed extra round-trip before every login attempt.
export const csrfInterceptor: HttpInterceptorFn = (req, next) => {
  const csrfTokenService = inject(CsrfTokenService);
  const api = inject(DispatcherSessionControllerService);
  const path = apiPath(req.url, api.configuration.basePath ?? '');

  if (path === undefined) {
    return next(req);
  }

  if (path === '/login') {
    // The server rotates the session's CSRF token on successful
    // authentication -- any token cached from before this login is stale
    // the instant it succeeds.
    return next(req).pipe(tap(() => csrfTokenService.clear()));
  }

  if (!UNSAFE_METHODS.has(req.method)) {
    return next(req);
  }

  return sendWithToken(req, next, csrfTokenService).pipe(
    tap(() => {
      if (path === '/logout') {
        csrfTokenService.clear();
      }
    }),
  );
};

function sendWithToken(
  req: HttpRequest<unknown>,
  next: HttpHandlerFn,
  csrfTokenService: CsrfTokenService,
  alreadyRetried = false,
): Observable<HttpEvent<unknown>> {
  return csrfTokenService.token().pipe(
    switchMap((token) => next(withCsrfHeader(req, token))),
    catchError((error: unknown) => {
      // A stale (rotated/expired) cached token is indistinguishable from
      // any other CSRF failure by status code alone -- refresh once and
      // retry once, never loop.
      if (!alreadyRetried && error instanceof HttpErrorResponse && error.status === 403) {
        csrfTokenService.clear();
        return sendWithToken(req, next, csrfTokenService, true);
      }
      return throwError(() => error);
    }),
  );
}

function withCsrfHeader(req: HttpRequest<unknown>, token: CsrfToken): HttpRequest<unknown> {
  return req.clone({ setHeaders: { [token.headerName]: token.token } });
}

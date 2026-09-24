import {
  ApplicationConfig,
  provideBrowserGlobalErrorListeners,
  provideZonelessChangeDetection,
} from '@angular/core';
import { provideRouter } from '@angular/router';
import { provideHttpClient, withFetch, withInterceptors, withNoXsrfProtection } from '@angular/common/http';
import { provideApi } from '@fleetpulse/api-client';
import { providePrimeNG } from 'primeng/config';
import { definePreset } from '@primeuix/themes';
import Aura from '@primeuix/themes/aura';
import { appRoutes } from './app.routes';
import { csrfInterceptor } from './core/auth/csrf.interceptor';
import { environment } from '../environments/environment';

// Design tokens: the mockups' primary `#2563eb` is exactly Tailwind's
// `blue-600`, and `@primeuix/themes/aura/base`'s own primitive `blue` scale
// (confirmed in node_modules) is byte-for-byte Tailwind's blue ramp -- so
// this re-points Aura's semantic `primary` color scale (default: emerald) at
// that existing primitive palette via token references, rather than
// hand-copying hex values. `color`/`hoverColor`/`activeColor`/
// `contrastColor` all derive from `{primary.*}` already in Aura's base
// preset, so they follow automatically.
const FleetPulsePreset = definePreset(Aura, {
  semantic: {
    primary: {
      50: '{blue.50}',
      100: '{blue.100}',
      200: '{blue.200}',
      300: '{blue.300}',
      400: '{blue.400}',
      500: '{blue.500}',
      600: '{blue.600}',
      700: '{blue.700}',
      800: '{blue.800}',
      900: '{blue.900}',
      950: '{blue.950}',
    },
  },
});

export const appConfig: ApplicationConfig = {
  providers: [
    provideZonelessChangeDetection(),
    provideBrowserGlobalErrorListeners(),
    provideRouter(appRoutes),
    // Angular's own built-in XSRF interceptor (on by default) reads the
    // token off a same-site-readable cookie, which does not exist here --
    // the console (fleetpulse-console.pages.dev) and api are different
    // sites, so `document.cookie` can never see whatever cookie the api's
    // origin sets, no matter its attributes. `withNoXsrfProtection()` turns
    // that no-op default off explicitly rather than leaving it silently
    // inert; `csrfInterceptor` (core/auth/csrf.interceptor.ts) is the real
    // implementation, fetching the token from CsrfTokenController's
    // response body instead of a cookie.
    provideHttpClient(withFetch(), withNoXsrfProtection(), withInterceptors([csrfInterceptor])),
    // `withCredentials: true`: the api sits behind a session cookie + a
    // CSRF header (SecurityConfig, delivered via CsrfTokenController --
    // csrfInterceptor above reads the exact header name from its response,
    // never hardcoded here), not a bearer token.
    // `basePath` was previously left unset, silently falling back to the
    // generated client's own `http://localhost:8099` default in every
    // build, including production -- this is what environment.ts/
    // environment.prod.ts (wired via project.json's `fileReplacements`) now
    // fixes.
    provideApi({ basePath: environment.apiUrl, withCredentials: true }),
    // Task 5.1: `libs/console-ui`'s components are built on PrimeNG. The
    // `dark mode via CSS class` selector is left at its default (`.p-dark`)
    // -- no dark-mode toggle exists yet, out of scope for this change.
    providePrimeNG({ theme: { preset: FleetPulsePreset } }),
  ],
};

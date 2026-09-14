import {
  ApplicationConfig,
  provideBrowserGlobalErrorListeners,
  provideZonelessChangeDetection,
} from '@angular/core';
import { provideRouter } from '@angular/router';
import { provideHttpClient, withFetch } from '@angular/common/http';
import { provideApi } from '@fleetpulse/api-client';
import { providePrimeNG } from 'primeng/config';
import Aura from '@primeuix/themes/aura';
import { appRoutes } from './app.routes';

export const appConfig: ApplicationConfig = {
  providers: [
    provideZonelessChangeDetection(),
    provideBrowserGlobalErrorListeners(),
    provideRouter(appRoutes),
    provideHttpClient(withFetch()),
    // `withCredentials: true`: the api sits behind a session cookie +
    // `X-XSRF-TOKEN` (SecurityConfig's `csrf().spa()`), not a bearer token.
    provideApi({ withCredentials: true }),
    // Task 5.1: `libs/console-ui`'s components are built on PrimeNG. The
    // `dark mode via CSS class` selector is left at its default (`.p-dark`)
    // -- no dark-mode toggle exists yet, out of scope for this change.
    providePrimeNG({ theme: { preset: Aura } }),
  ],
};

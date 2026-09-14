import {
  ApplicationConfig,
  provideBrowserGlobalErrorListeners,
  provideZonelessChangeDetection,
} from '@angular/core';
import { provideRouter } from '@angular/router';
import { provideHttpClient, withFetch } from '@angular/common/http';
import { provideApi } from '@fleetpulse/api-client';
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
  ],
};

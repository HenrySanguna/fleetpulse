import { Route } from '@angular/router';
import { authGuard } from './core/auth/auth.guard';

export const appRoutes: Route[] = [
  {
    path: 'login',
    loadComponent: () => import('./features/auth/pages/login-page.component').then((m) => m.LoginPageComponent),
  },
  {
    path: '',
    loadComponent: () => import('./core/layout/app-shell.component').then((m) => m.AppShellComponent),
    canActivate: [authGuard],
    children: [
      {
        path: '',
        loadComponent: () => import('./features/live-map/pages/live-map-page.component').then((m) => m.LiveMapPageComponent),
      },
      // Task 5.1 (+ 5.2 console half): the geofencing editor is its own route,
      // not a mode toggle inside the live map -- a stray click while tracking
      // vehicles must never be interpreted as placing a geofence vertex.
      {
        path: 'geofences',
        loadComponent: () =>
          import('./features/geofencing/pages/geofence-editor-page.component').then((m) => m.GeofenceEditorPageComponent),
      },
      // Alerts and Activity Report are shown in the nav rail (AppShellComponent,
      // copied from the Main.dc.html mockup) but their screens are separate
      // follow-up tasks -- not built here, so no child route for them yet.
    ],
  },
];

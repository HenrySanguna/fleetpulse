import { Route } from '@angular/router';

export const appRoutes: Route[] = [
  {
    path: '',
    loadComponent: () =>
      import('./features/live-map/pages/live-map-page.component').then((m) => m.LiveMapPageComponent),
  },
];

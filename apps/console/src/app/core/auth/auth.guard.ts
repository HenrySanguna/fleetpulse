import { inject } from '@angular/core';
import { Router, type CanActivateFn } from '@angular/router';
import { map } from 'rxjs';
import { AuthStore } from './auth.store';

export const authGuard: CanActivateFn = (_route, state) => {
  const authStore = inject(AuthStore);
  const router = inject(Router);

  return authStore
    .ensureChecked()
    .pipe(
      map(
        (authenticated) =>
          authenticated || router.createUrlTree(['/login'], { queryParams: { returnUrl: state.url } }),
      ),
    );
};

import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { AuthStore } from './core/auth/auth.store';
import { appRoutes } from './app.routes';

describe('appRoutes', () => {
  it('redirects unknown paths to the root route', () => {
    const wildcard = appRoutes[appRoutes.length - 1];

    expect(wildcard?.path).toBe('**');
    expect(wildcard?.redirectTo).toBe('');
  });

  // R3-route-structural-test: the test above only ever inspects the last
  // entry of the route config array -- it never proves the Router actually
  // resolves an unknown URL through it. This drives a real navigation
  // against the real `appRoutes` instead. `AuthStore` (authGuard's own
  // dependency on the redirect target's `canActivate`) is stubbed rather
  // than booting the app shell it would otherwise lazy-load: with no
  // `RouterOutlet` mounted, the Router still runs guards and resolves the
  // redirect but never constructs `AppShellComponent`/its children.
  it('navigates an unknown URL to the root route without throwing', async () => {
    TestBed.configureTestingModule({
      providers: [provideRouter(appRoutes), { provide: AuthStore, useValue: { ensureChecked: () => of(true) } }],
    });
    const router = TestBed.inject(Router);

    await expect(router.navigateByUrl('/this-route-does-not-exist')).resolves.toBe(true);

    expect(router.url).toBe('/');
  });
});

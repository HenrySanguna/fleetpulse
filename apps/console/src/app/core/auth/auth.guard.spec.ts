import { TestBed } from '@angular/core/testing';
import { Router, provideRouter, type UrlTree } from '@angular/router';
import type { Observable } from 'rxjs';
import { of } from 'rxjs';
import { AuthStore } from './auth.store';
import { authGuard } from './auth.guard';

describe('authGuard', () => {
  let authStore: { ensureChecked: ReturnType<typeof vi.fn> };

  beforeEach(() => {
    authStore = { ensureChecked: vi.fn() };
    TestBed.configureTestingModule({
      providers: [provideRouter([]), { provide: AuthStore, useValue: authStore }],
    });
  });

  function runGuard(url = '/geofences'): Observable<boolean | UrlTree> {
    return TestBed.runInInjectionContext(() => authGuard({} as never, { url } as never)) as Observable<boolean | UrlTree>;
  }

  it('allows activation when ensureChecked() resolves true', () => {
    authStore.ensureChecked.mockReturnValue(of(true));

    let result: boolean | UrlTree | undefined;
    runGuard().subscribe((value) => (result = value));

    expect(result).toBe(true);
  });

  it('redirects to /login with the requested URL as returnUrl when ensureChecked() resolves false', () => {
    authStore.ensureChecked.mockReturnValue(of(false));
    const router = TestBed.inject(Router);

    let result: boolean | UrlTree | undefined;
    runGuard('/alerts').subscribe((value) => (result = value));

    expect(result).not.toBe(true);
    expect((result as UrlTree).toString()).toBe(
      router.createUrlTree(['/login'], { queryParams: { returnUrl: '/alerts' } }).toString(),
    );
  });
});

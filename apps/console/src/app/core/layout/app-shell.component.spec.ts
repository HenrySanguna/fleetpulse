import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { By } from '@angular/platform-browser';
import { Drawer } from 'primeng/drawer';
import { Subject, of, throwError } from 'rxjs';
import type { DispatcherSelfView } from '@fleetpulse/api-client';
import { AuthService } from '../auth/auth.service';
import { AuthStore } from '../auth/auth.store';
import { FleetStore } from '../../features/live-map/services/fleet.store';
import { GeofenceStore } from '../../features/geofencing/services/geofence.store';
import { AlertsStore } from '../../features/alerts/services/alerts.store';
import { ActivityReportStore } from '../../features/activity-report/services/activity-report.store';
import { AppShellComponent } from './app-shell.component';

function click(fixture: ComponentFixture<AppShellComponent>, testId: string): void {
  // RouterLink's own host click listener (used by the nav `<a>`s) reads
  // `event.button`/modifier keys before navigating -- `undefined` throws
  // there, even though it was fine for the plain `<button>` clicks this
  // helper originally covered.
  fixture.debugElement
    .query(By.css(`[data-testid="${testId}"]`))
    ?.triggerEventHandler('click', { button: 0, ctrlKey: false, metaKey: false, shiftKey: false, altKey: false });
  fixture.detectChanges();
}

// BreakpointObserver's MediaMatcher reads `window.matchMedia` once, at
// construction -- jsdom has no real implementation, so without this it
// silently falls back to a no-op that always reports "not matching" (i.e.
// always desktop). Fakes `addListener`/`removeListener` (the legacy API
// @angular/cdk/layout actually calls), not `addEventListener`.
const originalMatchMedia = window.matchMedia;
let mediaListeners: ((event: { matches: boolean }) => void)[] = [];

function stubMatchMedia(matches: boolean): void {
  mediaListeners = [];
  window.matchMedia = vi.fn().mockImplementation((query: string) => ({
    matches,
    media: query,
    onchange: null,
    addListener: vi.fn((listener: (event: { matches: boolean }) => void) => mediaListeners.push(listener)),
    removeListener: vi.fn(),
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
    dispatchEvent: vi.fn(),
  }));
}

// Simulates a viewport resize crossing the breakpoint; BreakpointObserver
// debounces later emissions, hence the macrotask wait.
async function changeViewport(fixture: ComponentFixture<AppShellComponent>, matches: boolean): Promise<void> {
  mediaListeners.forEach((listener) => listener({ matches }));
  await new Promise((resolve) => setTimeout(resolve, 0));
  fixture.detectChanges();
}

describe('AppShellComponent', () => {
  let authService: { logout: ReturnType<typeof vi.fn> };
  let authStore: { dispatcher: ReturnType<typeof signal<DispatcherSelfView | null>>; clear: ReturnType<typeof vi.fn> };
  let fleetStore: { reset: ReturnType<typeof vi.fn> };
  let geofenceStore: { reset: ReturnType<typeof vi.fn> };
  let alertsStore: { reset: ReturnType<typeof vi.fn> };
  let activityReportStore: { reset: ReturnType<typeof vi.fn> };
  let router: Router;

  beforeEach(() => {
    authService = { logout: vi.fn() };
    authStore = { dispatcher: signal<DispatcherSelfView | null>(null), clear: vi.fn() };
    fleetStore = { reset: vi.fn() };
    geofenceStore = { reset: vi.fn() };
    alertsStore = { reset: vi.fn() };
    activityReportStore = { reset: vi.fn() };

    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        { provide: AuthService, useValue: authService },
        { provide: AuthStore, useValue: authStore },
        { provide: FleetStore, useValue: fleetStore },
        { provide: GeofenceStore, useValue: geofenceStore },
        { provide: AlertsStore, useValue: alertsStore },
        { provide: ActivityReportStore, useValue: activityReportStore },
      ],
    });

    router = TestBed.inject(Router);
    vi.spyOn(router, 'navigateByUrl').mockResolvedValue(true);
  });

  afterEach(() => {
    window.matchMedia = originalMatchMedia;
  });

  function createFixture(): ComponentFixture<AppShellComponent> {
    const fixture = TestBed.createComponent(AppShellComponent);
    fixture.detectChanges();
    return fixture;
  }

  it('renders nav links to the four shell routes', () => {
    const fixture = createFixture();
    const compiled = fixture.nativeElement as HTMLElement;

    expect(compiled.querySelector('a[href="/"]')).not.toBeNull();
    expect(compiled.querySelector('a[href="/geofences"]')).not.toBeNull();
    expect(compiled.querySelector('a[href="/alerts"]')).not.toBeNull();
    expect(compiled.querySelector('a[href="/activity"]')).not.toBeNull();
  });

  it('shows the signed-in dispatcher email and the mapped Spanish role label', () => {
    authStore.dispatcher.set({ id: 'd1', organizationId: 'org-1', email: 'despachador@transportesiberica.es', role: 'DISPATCHER' });

    const fixture = createFixture();
    const compiled = fixture.nativeElement as HTMLElement;

    expect(compiled.querySelector('[data-testid="rail-foot-email"]')?.textContent?.trim()).toBe('despachador@transportesiberica.es');
    expect(compiled.querySelector('[data-testid="rail-foot-role"]')?.textContent?.trim()).toBe('Despachador');
  });

  it('maps FLEET_ADMIN to the "Administrador de flota" label', () => {
    authStore.dispatcher.set({ id: 'd1', organizationId: 'org-1', email: 'admin@example.com', role: 'FLEET_ADMIN' });

    const fixture = createFixture();
    const compiled = fixture.nativeElement as HTMLElement;

    expect(compiled.querySelector('[data-testid="rail-foot-role"]')?.textContent?.trim()).toBe('Administrador de flota');
  });

  it('derives two-letter avatar initials from the email local part, uppercased', () => {
    authStore.dispatcher.set({ id: 'd1', organizationId: 'org-1', email: 'maria.ruiz@example.com', role: 'DISPATCHER' });

    const fixture = createFixture();
    const compiled = fixture.nativeElement as HTMLElement;

    expect(compiled.querySelector('.avatar')?.textContent?.trim()).toBe('MA');
  });

  it('signing out logs out, clears every feature store, and navigates to /login', () => {
    authService.logout.mockReturnValue(of(undefined));
    const fixture = createFixture();

    click(fixture, 'rail-logout');

    expect(authService.logout).toHaveBeenCalledTimes(1);
    expect(authStore.clear).toHaveBeenCalledTimes(1);
    // A second dispatcher signing in on the same tab must never see the
    // first one's org data -- every providedIn:'root' feature store has to
    // reset too, not just AuthStore.
    expect(fleetStore.reset).toHaveBeenCalledTimes(1);
    expect(geofenceStore.reset).toHaveBeenCalledTimes(1);
    expect(alertsStore.reset).toHaveBeenCalledTimes(1);
    expect(activityReportStore.reset).toHaveBeenCalledTimes(1);
    expect(router.navigateByUrl).toHaveBeenCalledWith('/login');
  });

  it('still signs out locally even if the logout request errors', () => {
    authService.logout.mockReturnValue(throwError(() => new Error('boom')));
    const fixture = createFixture();

    click(fixture, 'rail-logout');

    expect(authStore.clear).toHaveBeenCalledTimes(1);
    expect(router.navigateByUrl).toHaveBeenCalledWith('/login');
  });

  it('does not sign out before the logout request settles', () => {
    const pending = new Subject<void>();
    authService.logout.mockReturnValue(pending);
    const fixture = createFixture();

    click(fixture, 'rail-logout');

    expect(authStore.clear).not.toHaveBeenCalled();
    expect(router.navigateByUrl).not.toHaveBeenCalled();
  });

  describe('mobile navigation (<768px)', () => {
    it('renders the static rail and no toggle button on a desktop-sized viewport', () => {
      stubMatchMedia(false);
      const fixture = createFixture();
      const compiled = fixture.nativeElement as HTMLElement;

      expect(compiled.querySelector('nav.rail')).not.toBeNull();
      expect(compiled.querySelector('[data-testid="mobile-nav-toggle"]')).toBeNull();
      expect(fixture.debugElement.query(By.directive(Drawer))).toBeNull();
    });

    it('replaces the static rail with a toggle button and a closed drawer on a mobile-sized viewport', () => {
      stubMatchMedia(true);
      const fixture = createFixture();
      const compiled = fixture.nativeElement as HTMLElement;

      expect(compiled.querySelector('[data-testid="mobile-nav-toggle"]')).not.toBeNull();
      const drawer = fixture.debugElement.query(By.directive(Drawer))?.componentInstance as Drawer | undefined;
      expect(drawer?.visible).toBe(false);
    });

    it('opens the drawer when the toggle button is clicked', () => {
      stubMatchMedia(true);
      const fixture = createFixture();

      click(fixture, 'mobile-nav-toggle');

      const drawer = fixture.debugElement.query(By.directive(Drawer))?.componentInstance as Drawer | undefined;
      expect(drawer?.visible).toBe(true);
    });

    it('closes the drawer when a nav item is selected', () => {
      stubMatchMedia(true);
      const fixture = createFixture();
      click(fixture, 'mobile-nav-toggle');

      click(fixture, 'nav-geofences');

      const drawer = fixture.debugElement.query(By.directive(Drawer))?.componentInstance as Drawer | undefined;
      expect(drawer?.visible).toBe(false);
    });

    it('switches between the drawer and the static rail when the viewport crosses the breakpoint', async () => {
      stubMatchMedia(true);
      const fixture = createFixture();
      const compiled = fixture.nativeElement as HTMLElement;

      await changeViewport(fixture, false);
      expect(compiled.querySelector('[data-testid="mobile-nav-toggle"]')).toBeNull();
      expect(compiled.querySelector('nav.rail')).not.toBeNull();

      await changeViewport(fixture, true);
      expect(compiled.querySelector('[data-testid="mobile-nav-toggle"]')).not.toBeNull();
    });

    it('does not re-open the drawer after widening past the breakpoint and shrinking back', async () => {
      stubMatchMedia(true);
      const fixture = createFixture();
      click(fixture, 'mobile-nav-toggle');

      await changeViewport(fixture, false);
      await changeViewport(fixture, true);

      const drawer = fixture.debugElement.query(By.directive(Drawer))?.componentInstance as Drawer | undefined;
      expect(drawer?.visible).toBe(false);
    });
  });
});

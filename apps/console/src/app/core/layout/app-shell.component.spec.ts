import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { By } from '@angular/platform-browser';
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
  fixture.debugElement.query(By.css(`[data-testid="${testId}"]`))?.triggerEventHandler('click', undefined);
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
});

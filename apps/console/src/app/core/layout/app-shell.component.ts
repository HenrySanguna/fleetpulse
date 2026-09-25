import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { BreakpointObserver } from '@angular/cdk/layout';
import { map } from 'rxjs';
import { NgTemplateOutlet } from '@angular/common';
import { Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { Drawer } from 'primeng/drawer';
import { AuthService } from '../auth/auth.service';
import { AuthStore } from '../auth/auth.store';
import { FleetStore } from '../../features/live-map/services/fleet.store';
import { GeofenceStore } from '../../features/geofencing/services/geofence.store';
import { AlertsStore } from '../../features/alerts/services/alerts.store';
import { ActivityReportStore } from '../../features/activity-report/services/activity-report.store';

const FLEET_ADMIN_ROLE_LABEL = 'Administrador de flota';
const DEFAULT_ROLE_LABEL = 'Despachador';

// Prod QA T6: matches Tailwind's own `md` breakpoint (768px, unconfigured/
// default) so the CSS-only layout rules elsewhere and this JS-driven check
// never disagree about where "mobile" ends. The trailing `.98px` follows
// Angular CDK's own `Breakpoints` convention (see @angular/cdk/layout),
// avoiding both a `max-width` and a `min-width` query matching at once at
// exactly 768px.
const MOBILE_QUERY = '(max-width: 767.98px)';

// The nav rail + content shell for every authenticated route (everything
// under the `authGuard`-protected `''` route in app.routes.ts). Visual
// structure copied from the design canvas mockup's `Main.dc.html` `<nav
// class="rail">`; `DispatcherSelfView` has no organization-name field, so
// the rail-foot shows the real signed-in dispatcher's email instead of a
// fabricated org name.
@Component({
  selector: 'app-shell',
  imports: [NgTemplateOutlet, RouterLink, RouterLinkActive, RouterOutlet, Drawer],
  templateUrl: './app-shell.component.html',
  styleUrl: './app-shell.component.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AppShellComponent {
  private readonly authService = inject(AuthService);
  private readonly authStore = inject(AuthStore);
  private readonly router = inject(Router);
  private readonly fleetStore = inject(FleetStore);
  private readonly geofenceStore = inject(GeofenceStore);
  private readonly alertsStore = inject(AlertsStore);
  private readonly activityReportStore = inject(ActivityReportStore);
  private readonly breakpointObserver = inject(BreakpointObserver);

  protected readonly dispatcher = this.authStore.dispatcher;

  // Prod QA T6: below 768px the rail becomes a toggleable overlay drawer
  // instead of a permanent column (design.md's mobile finding: the rail ate
  // fixed horizontal space on phone widths). `isMatched(...)` seeds the
  // signal with the real synchronous value instead of a placeholder that
  // would briefly render the wrong branch on first paint.
  protected readonly isMobile = toSignal(
    this.breakpointObserver.observe(MOBILE_QUERY).pipe(map((state) => state.matches)),
    { initialValue: this.breakpointObserver.isMatched(MOBILE_QUERY) },
  );

  protected readonly mobileNavOpen = signal(false);

  protected readonly initials = computed(() => {
    const localPart = this.dispatcher()?.email?.split('@')[0];
    return localPart ? localPart.slice(0, 2).toUpperCase() : '';
  });

  protected readonly roleLabel = computed(() =>
    this.dispatcher()?.role === 'FLEET_ADMIN' ? FLEET_ADMIN_ROLE_LABEL : DEFAULT_ROLE_LABEL,
  );

  protected openMobileNav(): void {
    this.mobileNavOpen.set(true);
  }

  protected closeMobileNav(): void {
    this.mobileNavOpen.set(false);
  }

  protected logout(): void {
    this.authService.logout().subscribe({
      complete: () => this.finishLogout(),
      // Spring Security's default logout can resolve through a redirect
      // that ends up non-2xx (e.g. a following page that 404s) even though
      // the session/cookies were already invalidated server-side -- local
      // sign-out must still proceed regardless.
      error: () => this.finishLogout(),
    });
  }

  // Every providedIn:'root' feature store resets on logout -- otherwise a
  // second dispatcher signing in on the same tab briefly (or indefinitely,
  // if their own fetch fails) sees the previous dispatcher's org data.
  private finishLogout(): void {
    this.authStore.clear();
    this.fleetStore.reset();
    this.geofenceStore.reset();
    this.alertsStore.reset();
    this.activityReportStore.reset();
    this.router.navigateByUrl('/login');
  }
}

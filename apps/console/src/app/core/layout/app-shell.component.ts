import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { AuthService } from '../auth/auth.service';
import { AuthStore } from '../auth/auth.store';

const FLEET_ADMIN_ROLE_LABEL = 'Administrador de flota';
const DEFAULT_ROLE_LABEL = 'Despachador';

// The nav rail + content shell for every authenticated route (everything
// under the `authGuard`-protected `''` route in app.routes.ts). Visual
// structure copied from the design canvas mockup's `Main.dc.html` `<nav
// class="rail">`; `DispatcherSelfView` has no organization-name field, so
// the rail-foot shows the real signed-in dispatcher's email instead of a
// fabricated org name.
@Component({
  selector: 'app-shell',
  imports: [RouterLink, RouterLinkActive, RouterOutlet],
  templateUrl: './app-shell.component.html',
  styleUrl: './app-shell.component.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AppShellComponent {
  private readonly authService = inject(AuthService);
  private readonly authStore = inject(AuthStore);
  private readonly router = inject(Router);

  protected readonly dispatcher = this.authStore.dispatcher;

  protected readonly initials = computed(() => {
    const localPart = this.dispatcher()?.email?.split('@')[0];
    return localPart ? localPart.slice(0, 2).toUpperCase() : '';
  });

  protected readonly roleLabel = computed(() =>
    this.dispatcher()?.role === 'FLEET_ADMIN' ? FLEET_ADMIN_ROLE_LABEL : DEFAULT_ROLE_LABEL,
  );

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

  private finishLogout(): void {
    this.authStore.clear();
    this.router.navigateByUrl('/login');
  }
}

import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router } from '@angular/router';
import { AuthService } from '../../../core/auth/auth.service';
import { AuthStore } from '../../../core/auth/auth.store';

interface LoginFormControls {
  email: FormControl<string>;
  password: FormControl<string>;
}

// Recreates the design canvas's `Login.dc.html` (the dark/control-room
// direction -- the chosen final design, not an alternative). No shell
// chrome: this is a sibling of the shell route in app.routes.ts, not a
// child of it.
@Component({
  selector: 'app-login-page',
  imports: [ReactiveFormsModule],
  templateUrl: './login-page.component.html',
  styleUrl: './login-page.component.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class LoginPageComponent {
  private readonly authService = inject(AuthService);
  private readonly authStore = inject(AuthStore);
  private readonly router = inject(Router);

  protected readonly loading = signal(false);
  protected readonly loginError = signal<string | undefined>(undefined);

  protected readonly form = new FormGroup<LoginFormControls>({
    email: new FormControl('', { nonNullable: true, validators: [Validators.required, Validators.email] }),
    password: new FormControl('', { nonNullable: true, validators: [Validators.required] }),
  });

  // `this.form.valid` is a plain (non-signal) getter, so reading it directly
  // inside `computed()` would never mark the computed dirty when only the
  // form's validity changes (no tracked signal read) -- `toSignal` over
  // `statusChanges` makes validity itself a real, trackable signal so
  // `canSubmit` stays correctly reactive under zoneless change detection.
  private readonly formStatus = toSignal(this.form.statusChanges, { initialValue: this.form.status });

  protected readonly canSubmit = computed(() => !this.loading() && this.formStatus() === 'VALID');

  protected submit(): void {
    if (this.form.invalid || this.loading()) {
      this.form.markAllAsTouched();
      return;
    }

    const { email, password } = this.form.getRawValue();
    this.loading.set(true);
    this.loginError.set(undefined);

    this.authService.login(email, password).subscribe({
      next: () => this.loadDispatcherAndNavigate(),
      error: () => {
        this.loading.set(false);
        this.loginError.set('Credenciales inválidas.');
      },
    });
  }

  private loadDispatcherAndNavigate(): void {
    this.authService.me().subscribe({
      next: (dispatcher) => {
        this.authStore.setDispatcher(dispatcher);
        this.loading.set(false);
        this.router.navigateByUrl('/');
      },
      error: () => {
        this.loading.set(false);
        this.loginError.set('No se pudo iniciar la sesión. Inténtalo de nuevo.');
      },
    });
  }
}

import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, provideRouter } from '@angular/router';
import { By } from '@angular/platform-browser';
import { Subject, of, throwError } from 'rxjs';
import type { DispatcherSelfView } from '@fleetpulse/api-client';
import { AuthService } from '../../../core/auth/auth.service';
import { AuthStore } from '../../../core/auth/auth.store';
import { LoginPageComponent } from './login-page.component';

function setValue(fixture: ComponentFixture<LoginPageComponent>, testId: string, value: string): void {
  const input: HTMLInputElement = fixture.nativeElement.querySelector(`[data-testid="${testId}"]`);
  input.value = value;
  input.dispatchEvent(new Event('input'));
  fixture.detectChanges();
}

function submitDisabled(fixture: ComponentFixture<LoginPageComponent>): boolean {
  return (fixture.nativeElement.querySelector('[data-testid="login-submit"]') as HTMLButtonElement).disabled;
}

function submit(fixture: ComponentFixture<LoginPageComponent>): void {
  fixture.debugElement.query(By.css('form')).triggerEventHandler('submit', { preventDefault: () => undefined });
  fixture.detectChanges();
}

describe('LoginPageComponent', () => {
  let authService: { login: ReturnType<typeof vi.fn>; me: ReturnType<typeof vi.fn> };
  let authStore: { setDispatcher: ReturnType<typeof vi.fn> };
  let router: Router;
  let returnUrl: string | null;

  const dispatcher: DispatcherSelfView = { id: 'd1', organizationId: 'org-1', email: 'despachador@example.com', role: 'DISPATCHER' };

  beforeEach(() => {
    authService = { login: vi.fn(), me: vi.fn() };
    authStore = { setDispatcher: vi.fn() };
    returnUrl = null;

    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        { provide: AuthService, useValue: authService },
        { provide: AuthStore, useValue: authStore },
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { queryParamMap: { get: () => returnUrl } } },
        },
      ],
    });

    router = TestBed.inject(Router);
    vi.spyOn(router, 'navigateByUrl').mockResolvedValue(true);
  });

  function createFixture(): ComponentFixture<LoginPageComponent> {
    const fixture = TestBed.createComponent(LoginPageComponent);
    fixture.detectChanges();
    return fixture;
  }

  it('starts with the submit button disabled (empty form)', () => {
    const fixture = createFixture();

    expect(submitDisabled(fixture)).toBe(true);
  });

  it('enables the submit button once email and password are both valid', () => {
    const fixture = createFixture();

    setValue(fixture, 'login-email', 'despachador@example.com');
    setValue(fixture, 'login-password', 's3cret');

    expect(submitDisabled(fixture)).toBe(false);
  });

  it('keeps the submit button disabled for an invalid email', () => {
    const fixture = createFixture();

    setValue(fixture, 'login-email', 'not-an-email');
    setValue(fixture, 'login-password', 's3cret');

    expect(submitDisabled(fixture)).toBe(true);
  });

  it('on submit: logs in, loads the dispatcher, stores it, and navigates to /', () => {
    authService.login.mockReturnValue(of(undefined));
    authService.me.mockReturnValue(of(dispatcher));
    const fixture = createFixture();
    setValue(fixture, 'login-email', 'despachador@example.com');
    setValue(fixture, 'login-password', 's3cret');

    submit(fixture);

    expect(authService.login).toHaveBeenCalledWith('despachador@example.com', 's3cret');
    expect(authStore.setDispatcher).toHaveBeenCalledWith(dispatcher);
    expect(router.navigateByUrl).toHaveBeenCalledWith('/');
  });

  it('on submit: navigates to ?returnUrl when one was recorded by the guard', () => {
    returnUrl = '/alerts';
    authService.login.mockReturnValue(of(undefined));
    authService.me.mockReturnValue(of(dispatcher));
    const fixture = createFixture();
    setValue(fixture, 'login-email', 'despachador@example.com');
    setValue(fixture, 'login-password', 's3cret');

    submit(fixture);

    expect(router.navigateByUrl).toHaveBeenCalledWith('/alerts');
  });

  it('on submit: ignores a returnUrl that is not a same-app relative path (open-redirect guard)', () => {
    returnUrl = '//evil.example.com';
    authService.login.mockReturnValue(of(undefined));
    authService.me.mockReturnValue(of(dispatcher));
    const fixture = createFixture();
    setValue(fixture, 'login-email', 'despachador@example.com');
    setValue(fixture, 'login-password', 's3cret');

    submit(fixture);

    expect(router.navigateByUrl).toHaveBeenCalledWith('/');
  });

  it('shows an inline Spanish error and re-enables the form on a failed login (401)', () => {
    authService.login.mockReturnValue(throwError(() => ({ status: 401 })));
    const fixture = createFixture();
    setValue(fixture, 'login-email', 'despachador@example.com');
    setValue(fixture, 'login-password', 'wrong');

    submit(fixture);

    const errorEl = fixture.nativeElement.querySelector('[data-testid="login-error"]');
    expect(errorEl?.textContent).toContain('Credenciales inválidas');
    expect(submitDisabled(fixture)).toBe(false);
    expect(router.navigateByUrl).not.toHaveBeenCalled();
  });

  it('disables the submit button while the login request is in flight', () => {
    const pending = new Subject<void>();
    authService.login.mockReturnValue(pending);
    const fixture = createFixture();
    setValue(fixture, 'login-email', 'despachador@example.com');
    setValue(fixture, 'login-password', 's3cret');

    submit(fixture);

    expect(submitDisabled(fixture)).toBe(true);
  });
});

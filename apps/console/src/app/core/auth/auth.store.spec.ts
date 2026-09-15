import { TestBed } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { Subject, of } from 'rxjs';
import type { DispatcherSelfView } from '@fleetpulse/api-client';
import { AuthService } from './auth.service';
import { AuthStore } from './auth.store';

describe('AuthStore', () => {
  let authService: { me: ReturnType<typeof vi.fn> };
  let store: InstanceType<typeof AuthStore>;

  const dispatcher: DispatcherSelfView = { id: 'd1', organizationId: 'org-1', email: 'dispatcher@example.com', role: 'DISPATCHER' };

  beforeEach(() => {
    authService = { me: vi.fn() };
    TestBed.configureTestingModule({
      providers: [{ provide: AuthService, useValue: authService }],
    });
    store = TestBed.inject(AuthStore);
  });

  it('starts unchecked, with no dispatcher', () => {
    expect(store.checked()).toBe(false);
    expect(store.dispatcher()).toBeNull();
  });

  it('ensureChecked() calls me() once, stores the dispatcher, and resolves true on success', () => {
    authService.me.mockReturnValue(of(dispatcher));

    let result: boolean | undefined;
    store.ensureChecked().subscribe((authenticated) => (result = authenticated));

    expect(authService.me).toHaveBeenCalledTimes(1);
    expect(result).toBe(true);
    expect(store.checked()).toBe(true);
    expect(store.dispatcher()).toEqual(dispatcher);
  });

  it('ensureChecked() clears the dispatcher, caches checked=true, and resolves false on a real 401', () => {
    const errorSubject = new Subject<never>();
    authService.me.mockReturnValue(errorSubject);

    let result: boolean | undefined;
    store.ensureChecked().subscribe((authenticated) => (result = authenticated));
    errorSubject.error(new HttpErrorResponse({ status: 401 }));

    expect(result).toBe(false);
    expect(store.checked()).toBe(true);
    expect(store.dispatcher()).toBeNull();
  });

  // A network blip, timeout, or 5xx is not proof the dispatcher is logged
  // out -- caching checked=true for one would permanently lock a still-valid
  // session out until a full reload (the bug this test guards against).
  it('ensureChecked() resolves false but leaves checked=false on a non-401 failure, so a later call retries', () => {
    const errorSubject = new Subject<never>();
    authService.me.mockReturnValue(errorSubject);

    let result: boolean | undefined;
    store.ensureChecked().subscribe((authenticated) => (result = authenticated));
    errorSubject.error(new HttpErrorResponse({ status: 0 }));

    expect(result).toBe(false);
    expect(store.checked()).toBe(false);
    expect(store.dispatcher()).toBeNull();

    authService.me.mockReturnValue(of(dispatcher));
    store.ensureChecked().subscribe();
    expect(authService.me).toHaveBeenCalledTimes(2);
  });

  it('ensureChecked() only calls me() once even when called concurrently before it resolves', () => {
    const subject = new Subject<DispatcherSelfView>();
    authService.me.mockReturnValue(subject);

    const results: boolean[] = [];
    store.ensureChecked().subscribe((v) => results.push(v));
    store.ensureChecked().subscribe((v) => results.push(v));

    expect(authService.me).toHaveBeenCalledTimes(1);

    subject.next(dispatcher);
    subject.complete();

    expect(results).toEqual([true, true]);
  });

  it('ensureChecked() does not call me() again once already checked', () => {
    authService.me.mockReturnValue(of(dispatcher));
    store.ensureChecked().subscribe();

    store.ensureChecked().subscribe();

    expect(authService.me).toHaveBeenCalledTimes(1);
  });

  it('setDispatcher() records the dispatcher and marks the store checked without calling me()', () => {
    store.setDispatcher(dispatcher);

    expect(store.checked()).toBe(true);
    expect(store.dispatcher()).toEqual(dispatcher);
    expect(authService.me).not.toHaveBeenCalled();
  });

  it('clear() resets the dispatcher and checked flag, allowing a fresh ensureChecked() to call me() again', () => {
    store.setDispatcher(dispatcher);

    store.clear();

    expect(store.checked()).toBe(false);
    expect(store.dispatcher()).toBeNull();

    authService.me.mockReturnValue(of(dispatcher));
    store.ensureChecked().subscribe();
    expect(authService.me).toHaveBeenCalledTimes(1);
  });
});

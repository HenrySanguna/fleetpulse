import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import type { DispatcherSelfView } from '@fleetpulse/api-client';
import { DispatcherSessionControllerService } from '@fleetpulse/api-client';
import { AuthService } from './auth.service';

// Same real-HttpClient-plus-testing-backend approach as
// GeofenceService/geofence.service.spec.ts -- exercises the actual request
// object HttpClient builds (method, body, headers, responseType), which a
// test that fakes HttpClient itself would never catch.
describe('AuthService', () => {
  let service: AuthService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: DispatcherSessionControllerService,
          useValue: { configuration: { basePath: 'http://localhost:8099', withCredentials: true } },
        },
      ],
    });

    service = TestBed.inject(AuthService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('login() POSTs username/password as a form-urlencoded body, credentialed, expecting a text response', () => {
    let completed = false;
    service.login('dispatcher@example.com', 's3cret').subscribe(() => (completed = true));

    const request = httpMock.expectOne('http://localhost:8099/login');
    expect(request.request.method).toBe('POST');
    expect(request.request.withCredentials).toBe(true);
    expect(request.request.responseType).toBe('text');
    expect(request.request.body).toBeInstanceOf(URLSearchParams);
    expect((request.request.body as URLSearchParams).get('username')).toBe('dispatcher@example.com');
    expect((request.request.body as URLSearchParams).get('password')).toBe('s3cret');

    request.flush('');

    expect(completed).toBe(true);
  });

  it('login() surfaces a 401 as an error', () => {
    let error: unknown;
    service.login('dispatcher@example.com', 'wrong').subscribe({ error: (err: unknown) => (error = err) });

    httpMock.expectOne('http://localhost:8099/login').flush('', { status: 401, statusText: 'Unauthorized' });

    expect(error).toBeDefined();
  });

  it('me() issues a plain, credentialed GET with responseType "json"', () => {
    let result: DispatcherSelfView | undefined;
    service.me().subscribe((dispatcher) => (result = dispatcher));

    const request = httpMock.expectOne('http://localhost:8099/api/dispatchers/me');
    expect(request.request.method).toBe('GET');
    expect(request.request.withCredentials).toBe(true);
    expect(request.request.responseType).toBe('json');

    const dispatcher: DispatcherSelfView = { id: 'd1', organizationId: 'org-1', email: 'dispatcher@example.com', role: 'DISPATCHER' };
    request.flush(dispatcher);

    expect(result).toEqual(dispatcher);
  });

  it('logout() POSTs to /logout, credentialed, expecting a text response', () => {
    let completed = false;
    service.logout().subscribe(() => (completed = true));

    const request = httpMock.expectOne('http://localhost:8099/logout');
    expect(request.request.method).toBe('POST');
    expect(request.request.withCredentials).toBe(true);
    expect(request.request.responseType).toBe('text');

    request.flush('');

    expect(completed).toBe(true);
  });
});

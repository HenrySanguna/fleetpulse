import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { DispatcherSessionControllerService } from '@fleetpulse/api-client';
import { csrfInterceptor } from './csrf.interceptor';

// Same real-HttpClient-plus-testing-backend approach as AuthService/
// GeofenceService specs -- exercises the actual request the interceptor
// produces (headers, method, url), which a test that fakes HttpClient
// itself would never catch. CsrfTokenService is NOT mocked: it is the real
// singleton, so these tests also cover its in-flight-sharing/caching
// behavior end to end.
describe('csrfInterceptor', () => {
  const API_BASE_URL = 'http://localhost:8099';

  let http: HttpClient;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([csrfInterceptor])),
        provideHttpClientTesting(),
        {
          provide: DispatcherSessionControllerService,
          useValue: { configuration: { basePath: API_BASE_URL, withCredentials: true } },
        },
      ],
    });

    http = TestBed.inject(HttpClient);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('attaches X-XSRF-TOKEN to an unsafe request to the API, fetched from GET /api/csrf', () => {
    let result: unknown;
    http.post(`${API_BASE_URL}/api/geofences`, { name: 'Depot' }).subscribe((response) => (result = response));

    const csrfRequest = httpMock.expectOne(`${API_BASE_URL}/api/csrf`);
    expect(csrfRequest.request.method).toBe('GET');
    expect(csrfRequest.request.withCredentials).toBe(true);
    csrfRequest.flush({ headerName: 'X-XSRF-TOKEN', token: 'token-1' });

    const request = httpMock.expectOne(`${API_BASE_URL}/api/geofences`);
    expect(request.request.headers.get('X-XSRF-TOKEN')).toBe('token-1');
    request.flush({ id: 'g1' });

    expect(result).toEqual({ id: 'g1' });
  });

  it('does not attach a CSRF header, or fetch a token, for a GET request', () => {
    http.get(`${API_BASE_URL}/api/geofences`).subscribe();

    const request = httpMock.expectOne(`${API_BASE_URL}/api/geofences`);
    expect(request.request.headers.has('X-XSRF-TOKEN')).toBe(false);
    request.flush([]);
  });

  it('does not attach a CSRF header for a request outside the API base URL', () => {
    http.post('https://tiles.example.com/style.json', {}).subscribe();

    const request = httpMock.expectOne('https://tiles.example.com/style.json');
    expect(request.request.headers.has('X-XSRF-TOKEN')).toBe(false);
    request.flush({});
  });

  it('shares one in-flight token fetch between concurrent unsafe requests', () => {
    http.post(`${API_BASE_URL}/api/geofences`, { name: 'A' }).subscribe();
    http.post(`${API_BASE_URL}/api/geofences`, { name: 'B' }).subscribe();

    // expectOne throws if more than one matching request was made -- proves
    // only one GET /api/csrf happened for both concurrent POSTs.
    const csrfRequest = httpMock.expectOne(`${API_BASE_URL}/api/csrf`);
    csrfRequest.flush({ headerName: 'X-XSRF-TOKEN', token: 'shared-token' });

    const requests = httpMock.match(`${API_BASE_URL}/api/geofences`);
    expect(requests).toHaveLength(2);
    for (const request of requests) {
      expect(request.request.headers.get('X-XSRF-TOKEN')).toBe('shared-token');
      request.flush({});
    }
  });

  it('clears the cached token after a successful /login, forcing a fresh fetch on the next unsafe request', () => {
    http.post(`${API_BASE_URL}/api/geofences`, {}).subscribe();
    httpMock.expectOne(`${API_BASE_URL}/api/csrf`).flush({ headerName: 'X-XSRF-TOKEN', token: 'pre-login-token' });
    httpMock.expectOne(`${API_BASE_URL}/api/geofences`).flush({});

    http.post(`${API_BASE_URL}/login`, new URLSearchParams(), { responseType: 'text' }).subscribe();
    httpMock.expectOne(`${API_BASE_URL}/login`).flush('');

    http.post(`${API_BASE_URL}/api/geofences`, {}).subscribe();
    const refetch = httpMock.expectOne(`${API_BASE_URL}/api/csrf`);
    expect(refetch.request.method).toBe('GET');
    refetch.flush({ headerName: 'X-XSRF-TOKEN', token: 'post-login-token' });
    httpMock.expectOne(`${API_BASE_URL}/api/geofences`).flush({});
  });

  it('does not fetch or attach a token for /login itself', () => {
    http.post(`${API_BASE_URL}/login`, new URLSearchParams(), { responseType: 'text' }).subscribe();

    const request = httpMock.expectOne(`${API_BASE_URL}/login`);
    expect(request.request.headers.has('X-XSRF-TOKEN')).toBe(false);
    request.flush('');
  });

  it('clears the cached token after logout, forcing a fresh fetch on the next unsafe request', () => {
    http.post(`${API_BASE_URL}/api/geofences`, {}).subscribe();
    httpMock.expectOne(`${API_BASE_URL}/api/csrf`).flush({ headerName: 'X-XSRF-TOKEN', token: 'token-1' });
    httpMock.expectOne(`${API_BASE_URL}/api/geofences`).flush({});

    http.post(`${API_BASE_URL}/logout`, new URLSearchParams(), { responseType: 'text' }).subscribe();
    const logoutRequest = httpMock.expectOne(`${API_BASE_URL}/logout`);
    expect(logoutRequest.request.headers.get('X-XSRF-TOKEN')).toBe('token-1');
    logoutRequest.flush('');

    http.post(`${API_BASE_URL}/api/geofences`, {}).subscribe();
    const refetch = httpMock.expectOne(`${API_BASE_URL}/api/csrf`);
    refetch.flush({ headerName: 'X-XSRF-TOKEN', token: 'token-2' });
    httpMock.expectOne(`${API_BASE_URL}/api/geofences`).flush({});
  });

  it('refreshes the token once and retries once on a 403 response', () => {
    let result: unknown;
    http.post(`${API_BASE_URL}/api/geofences`, {}).subscribe((response) => (result = response));

    httpMock.expectOne(`${API_BASE_URL}/api/csrf`).flush({ headerName: 'X-XSRF-TOKEN', token: 'stale-token' });
    const firstAttempt = httpMock.expectOne(`${API_BASE_URL}/api/geofences`);
    expect(firstAttempt.request.headers.get('X-XSRF-TOKEN')).toBe('stale-token');
    firstAttempt.flush('Forbidden', { status: 403, statusText: 'Forbidden' });

    const retryCsrfRequest = httpMock.expectOne(`${API_BASE_URL}/api/csrf`);
    retryCsrfRequest.flush({ headerName: 'X-XSRF-TOKEN', token: 'fresh-token' });
    const secondAttempt = httpMock.expectOne(`${API_BASE_URL}/api/geofences`);
    expect(secondAttempt.request.headers.get('X-XSRF-TOKEN')).toBe('fresh-token');
    secondAttempt.flush({ id: 'g1' });

    expect(result).toEqual({ id: 'g1' });
  });

  it('does not loop when the retried request also gets a 403', () => {
    let error: unknown;
    http.post(`${API_BASE_URL}/api/geofences`, {}).subscribe({ error: (err: unknown) => (error = err) });

    httpMock.expectOne(`${API_BASE_URL}/api/csrf`).flush({ headerName: 'X-XSRF-TOKEN', token: 'token-1' });
    httpMock.expectOne(`${API_BASE_URL}/api/geofences`).flush('Forbidden', { status: 403, statusText: 'Forbidden' });

    httpMock.expectOne(`${API_BASE_URL}/api/csrf`).flush({ headerName: 'X-XSRF-TOKEN', token: 'token-2' });
    httpMock.expectOne(`${API_BASE_URL}/api/geofences`).flush('Forbidden', { status: 403, statusText: 'Forbidden' });

    // afterEach's httpMock.verify() proves no third /api/csrf or
    // /api/geofences request was ever made -- the retry did not loop.
    expect((error as { status: number }).status).toBe(403);
  });
});

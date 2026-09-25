import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import type { ActivityReportResponse } from '@fleetpulse/api-client';
import { ActivityReportControllerService, FleetStateControllerService } from '@fleetpulse/api-client';
import type { ActivityReport } from '../models/activity-report.model';
import { ActivityReportService } from './activity-report.service';

// Same real-HttpClient-+-testing-backend recipe as AlertsService's/
// GeofenceService's own specs: exercises the actual response->model mapping
// (toReport()/toInProgressTrip(), private to this file) rather than a test
// that mocks ActivityReportService itself.
describe('ActivityReportService', () => {
  let service: ActivityReportService;
  let httpMock: HttpTestingController;

  const baseResponse: ActivityReportResponse = {
    vehicleId: 'VH-1042',
    summary: { totalDistanceKm: 120, movingMinutes: 90, idleMinutes: 10, avgSpeedKmh: 40, maxSpeedKmh: 80 },
    dailyDistances: [{ day: '2026-01-01', distanceKm: 40 }],
    trips: [
      {
        id: 't1',
        startedAt: '2026-01-01T08:00:00Z',
        endedAt: '2026-01-01T09:00:00Z',
        distanceKm: 40,
        durationMinutes: 60,
        idleMinutes: 5,
        maxSpeedKmh: 80,
      },
    ],
  };

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: ActivityReportControllerService,
          useValue: { configuration: { basePath: 'http://localhost:8099', withCredentials: true } },
        },
        {
          provide: FleetStateControllerService,
          useValue: { configuration: { basePath: 'http://localhost:8099', withCredentials: true } },
        },
      ],
    });

    service = TestBed.inject(ActivityReportService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  // `toReport()` runs synchronously inside the `map()` piped onto
  // `getJson()`, so a `required()` throw surfaces as an RxJS error
  // notification, not a JS exception `flush()` itself raises -- captured
  // via an explicit `error` callback rather than `expect(...).toThrow()`,
  // which would miss it and leave it an unhandled rejection instead.
  function flushReport(response: ActivityReportResponse): { result?: ActivityReport; error?: unknown } {
    const outcome: { result?: ActivityReport; error?: unknown } = {};
    service.getReport('VH-1042', '2026-01-01T00:00:00Z', '2026-01-02T00:00:00Z').subscribe({
      next: (report) => (outcome.result = report),
      error: (error: unknown) => (outcome.error = error),
    });

    // `getReport()` bakes `from`/`to` into the URL string itself (see its
    // own comment), so `req.url` is the raw string including the
    // (percent-encoded) query -- matched by prefix rather than spelling out
    // the exact encoding here.
    httpMock.expectOne((req) => req.url.startsWith('http://localhost:8099/api/vehicles/VH-1042/activity-report')).flush(response);
    return outcome;
  }

  it('maps a full response into an ActivityReport', () => {
    const { result } = flushReport(baseResponse);

    expect(result).toEqual<ActivityReport>({
      vehicleId: 'VH-1042',
      summary: { totalDistanceKm: 120, movingMinutes: 90, idleMinutes: 10, avgSpeedKmh: 40, maxSpeedKmh: 80 },
      dailyDistances: [{ day: '2026-01-01', distanceKm: 40 }],
      trips: [
        { id: 't1', startedAt: '2026-01-01T08:00:00Z', endedAt: '2026-01-01T09:00:00Z', distanceKm: 40, durationMinutes: 60, idleMinutes: 5, maxSpeedKmh: 80 },
      ],
      inProgressTrip: undefined,
    });
  });

  // Task 10: unlike every other field on the response, inProgressTrip is
  // genuinely optional on the wire -- null/absent for a past range or a
  // vehicle that is not currently in a trip.
  it('omits inProgressTrip when the response does not include one', () => {
    const { result } = flushReport({ ...baseResponse, inProgressTrip: undefined });

    expect(result?.inProgressTrip).toBeUndefined();
  });

  it('maps inProgressTrip when the response includes one', () => {
    const { result } = flushReport({
      ...baseResponse,
      inProgressTrip: { startedAt: '2026-01-02T07:00:00Z', distanceKm: 12, durationMinutes: 20, idleMinutes: 2, maxSpeedKmh: 55 },
    });

    expect(result?.inProgressTrip).toEqual({
      startedAt: '2026-01-02T07:00:00Z',
      distanceKm: 12,
      durationMinutes: 20,
      idleMinutes: 2,
      maxSpeedKmh: 55,
    });
  });

  // Every field on the generated response DTOs is typed optional (no
  // no-`required`-array OpenAPI gap, see toInProgressTrip's own comment) --
  // a present inProgressTrip missing one of its own required fields is a
  // malformed response worth failing loudly on, same as every other
  // required() call in this file.
  it('throws when a present inProgressTrip is missing a required field', () => {
    const { result, error } = flushReport({
      ...baseResponse,
      inProgressTrip: { distanceKm: 12, durationMinutes: 20, idleMinutes: 2, maxSpeedKmh: 55 },
    });

    expect(result).toBeUndefined();
    expect((error as Error).message).toBe('ActivityReportService: response missing required field "inProgressTrip.startedAt"');
  });
});

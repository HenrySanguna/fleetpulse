import { ApplicationRef } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting, TestRequest } from '@angular/common/http/testing';
import { FleetStore } from './fleet.store';
import { TRACK_REFRESH_INTERVAL_MS, TRACK_WINDOW_HOURS, VehicleTrackService } from './vehicle-track.service';

describe('VehicleTrackService', () => {
  let httpMock: HttpTestingController;
  let service: VehicleTrackService;
  let store: InstanceType<typeof FleetStore>;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });

    service = TestBed.inject(VehicleTrackService);
    store = TestBed.inject(FleetStore);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('issues no request while no vehicle is selected', () => {
    TestBed.tick();

    httpMock.expectNone(() => true);
    expect(service.trackFeatures()).toEqual([]);
  });

  // Task 3.3, extended by prod QA (2026-09-24): bounded window. Real time
  // (not faked) is used here -- faking timers stalls httpResource/
  // ApplicationRef.whenStable() below -- so the window is asserted as an
  // exact TRACK_WINDOW_HOURS span landing around "now", not against a fixed
  // clock.
  it('requests a bounded window of exactly TRACK_WINDOW_HOURS ending around now for the selected vehicle', () => {
    const before = Date.now();
    store.selectVehicle('v1');
    TestBed.tick();
    const after = Date.now();

    const firstRequest = httpMock.expectOne((req) => req.url === 'http://localhost:8099/api/vehicles/v1/track');
    expect(firstRequest.request.method).toBe('GET');
    const to = Date.parse(firstRequest.request.params.get('to') ?? '');
    const from = Date.parse(firstRequest.request.params.get('from') ?? '');
    expect(to).toBeGreaterThanOrEqual(before);
    expect(to).toBeLessThanOrEqual(after);
    expect(to - from).toBe(TRACK_WINDOW_HOURS * 60 * 60 * 1000);
    firstRequest.flush([]);
  });

  it('re-requests a fresh window on reselection', () => {
    store.selectVehicle('v1');
    TestBed.tick();
    httpMock.expectOne((req) => req.url === 'http://localhost:8099/api/vehicles/v1/track').flush([]);

    store.selectVehicle('v2');
    TestBed.tick();

    httpMock.expectOne((req) => req.url === 'http://localhost:8099/api/vehicles/v2/track').flush([]);
  });

  // Task 4.6
  it('maps the flushed response into segment features', async () => {
    store.selectVehicle('v1');
    TestBed.tick();

    httpMock.expectOne((req) => req.url === 'http://localhost:8099/api/vehicles/v1/track').flush([
      { lat: 1, lon: 2, recordedAt: '2026-01-01T11:00:00Z' },
      { lat: 1.001, lon: 2.001, recordedAt: '2026-01-01T11:00:10Z' },
    ]);
    // httpResource resolves the flushed response through a microtask (unlike
    // the toObservable()-backed effects elsewhere in this feature, which
    // TestBed.tick() flushes synchronously) -- waiting for app stability is
    // the documented way to await a resource settling in a test.
    await TestBed.inject(ApplicationRef).whenStable();

    expect(service.trackFeatures()).toEqual([
      {
        type: 'Feature',
        geometry: {
          type: 'LineString',
          coordinates: [
            [2, 1],
            [2.001, 1.001],
          ],
        },
        properties: {},
      },
    ]);
  });

  // Decided scope: implausible jumps split the line rather than drawing a
  // straight line across them -- vehicle-track.util.spec.ts covers the split
  // logic exhaustively, this only proves the service wires it through.
  it('splits the reported points into separate segment features at an implausible jump', async () => {
    store.selectVehicle('v1');
    TestBed.tick();

    httpMock.expectOne((req) => req.url === 'http://localhost:8099/api/vehicles/v1/track').flush([
      { lat: 0, lon: 0, recordedAt: '2026-01-01T11:00:00Z' },
      { lat: 0.001, lon: 0, recordedAt: '2026-01-01T11:00:10Z' },
      // ~7000 km in 30 min -- an implausible speed jump.
      { lat: 50, lon: 50, recordedAt: '2026-01-01T11:30:00Z' },
      { lat: 50.001, lon: 50, recordedAt: '2026-01-01T11:30:10Z' },
    ]);
    await TestBed.inject(ApplicationRef).whenStable();

    expect(service.trackFeatures()).toHaveLength(2);
  });
});

// Prod QA follow-up (R3-003). A separate TestBed configuration (rather than
// a nested describe reusing the outer beforeEach's default-interval module)
// so `TRACK_REFRESH_INTERVAL_MS` can be overridden to a few milliseconds
// before the module is instantiated. Real time, not faked, is used to drive
// the tick for the same reason the window-bound test above avoids
// `vi.useFakeTimers()`.
describe('VehicleTrackService refresh tick', () => {
  let httpMock: HttpTestingController;
  let store: InstanceType<typeof FleetStore>;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), { provide: TRACK_REFRESH_INTERVAL_MS, useValue: 15 }],
    });

    TestBed.inject(VehicleTrackService);
    store = TestBed.inject(FleetStore);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('rolls the from/to window forward once the refresh interval ticks, for the same selected vehicle', async () => {
    store.selectVehicle('v1');
    TestBed.tick();
    const firstRequest = httpMock.expectOne((req) => req.url === 'http://localhost:8099/api/vehicles/v1/track');
    const firstTo = Date.parse(firstRequest.request.params.get('to') ?? '');
    firstRequest.flush([]);

    // The 15 ms interval may fire more than once during a real (unfaked)
    // wait, so this asserts against whichever request landed last rather
    // than assuming exactly one -- the point under test is that the window
    // moved forward, not how many ticks it took. Polls (up to 2 s) instead
    // of a fixed sleep so a loaded machine cannot starve the interval.
    //
    // `httpResource` cancels a still-pending request once a later tick
    // supersedes it, so only the last (non-cancelled) one can be flushed --
    // `match()` still needs to see every one of them, cancelled or not, for
    // `httpMock.verify()` below to pass.
    const laterRequests: TestRequest[] = [];
    const deadline = Date.now() + 2000;
    while (laterRequests.length === 0 && Date.now() < deadline) {
      await new Promise((resolve) => setTimeout(resolve, 20));
      TestBed.tick();
      laterRequests.push(...httpMock.match((req) => req.url === 'http://localhost:8099/api/vehicles/v1/track'));
    }
    expect(laterRequests.length).toBeGreaterThan(0);
    const lastRequest = laterRequests[laterRequests.length - 1];
    const lastTo = Date.parse(lastRequest.request.params.get('to') ?? '');
    expect(lastTo).toBeGreaterThan(firstTo);
    laterRequests.filter((request) => !request.cancelled).forEach((request) => request.flush([]));
  });
});

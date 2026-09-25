import { ApplicationRef } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { FleetStore } from './fleet.store';
import { TRACK_WINDOW_HOURS, VehicleTrackService } from './vehicle-track.service';

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

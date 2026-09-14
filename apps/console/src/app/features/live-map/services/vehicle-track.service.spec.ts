import { ApplicationRef } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import type { TrackPointResponse } from '@fleetpulse/api-client';
import { FleetStore } from './fleet.store';
import { VehicleTrackService, toTrackLineFeature } from './vehicle-track.service';

describe('toTrackLineFeature', () => {
  it('returns undefined for no points or a single point (no line to draw)', () => {
    expect(toTrackLineFeature(undefined)).toBeUndefined();
    expect(toTrackLineFeature([])).toBeUndefined();
    expect(toTrackLineFeature([{ lat: 1, lon: 2, recordedAt: 'a' }])).toBeUndefined();
  });

  // Task 4.6
  it('maps reported points to a LineString in [lon, lat] GeoJSON order, dropping incomplete points', () => {
    const points: TrackPointResponse[] = [
      { lat: 1, lon: 2, recordedAt: 'a' },
      { lat: undefined, lon: undefined, recordedAt: 'b' },
      { lat: 3, lon: 4, recordedAt: 'c' },
    ];

    expect(toTrackLineFeature(points)).toEqual({
      type: 'Feature',
      geometry: {
        type: 'LineString',
        coordinates: [
          [2, 1],
          [4, 3],
        ],
      },
      properties: {},
    });
  });
});

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
    expect(service.trackLine()).toBeUndefined();
  });

  // Task 3.3
  it('requests the track for the selected vehicle and re-requests on reselection', async () => {
    store.selectVehicle('v1');
    TestBed.tick();

    const firstRequest = httpMock.expectOne('http://localhost:8099/api/vehicles/v1/track');
    expect(firstRequest.request.method).toBe('GET');
    firstRequest.flush([
      { lat: 1, lon: 2, recordedAt: 'a' },
      { lat: 3, lon: 4, recordedAt: 'b' },
    ]);
    // httpResource resolves the flushed response through a microtask (unlike
    // the toObservable()-backed effects elsewhere in this feature, which
    // TestBed.tick() flushes synchronously) -- waiting for app stability is
    // the documented way to await a resource settling in a test.
    await TestBed.inject(ApplicationRef).whenStable();

    expect(service.trackLine()).toEqual({
      type: 'Feature',
      geometry: {
        type: 'LineString',
        coordinates: [
          [2, 1],
          [4, 3],
        ],
      },
      properties: {},
    });

    store.selectVehicle('v2');
    TestBed.tick();

    httpMock.expectOne('http://localhost:8099/api/vehicles/v2/track').flush([]);
  });
});

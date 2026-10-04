import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import type { GeofenceRequest, GeofenceResponse } from '@fleetpulse/api-client';
import { GeofenceControllerService } from '@fleetpulse/api-client';
import { GeofenceService } from './geofence.service';
import { GeofenceStore } from './geofence.store';

// Unlike FleetStartupService's own spec (which fakes `HttpClient` directly),
// this exercises the REAL HttpClient + testing backend: the bug this WU
// discovered (GeofenceControllerService.list()/create()/update() all
// silently requesting `responseType: 'blob'`, core/http/api-client-json-get.ts)
// is invisible to a test that fakes `HttpClient.get`/`post` itself -- it only
// ever shows up in the request object HttpClient actually constructs. This
// is the nearest thing to a runtime boundary proof available without a live
// backend.
describe('GeofenceService', () => {
  let service: GeofenceService;
  let store: InstanceType<typeof GeofenceStore>;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: GeofenceControllerService,
          useValue: { configuration: { basePath: 'http://localhost:8099', withCredentials: true } },
        },
      ],
    });

    service = TestBed.inject(GeofenceService);
    store = TestBed.inject(GeofenceStore);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('load() issues a plain, credentialed GET with responseType "json" and stores the parsed response', () => {
    service.load();

    const request = httpMock.expectOne('http://localhost:8099/api/geofences');
    expect(request.request.method).toBe('GET');
    expect(request.request.withCredentials).toBe(true);
    expect(request.request.responseType).toBe('json');

    const geofences: GeofenceResponse[] = [{ id: 'g1', name: 'Depot' }];
    request.flush(geofences);

    expect(store.geofences()).toEqual(geofences);
  });

  it('load() records an error, without throwing, when the request fails', () => {
    service.load();

    httpMock.expectOne('http://localhost:8099/api/geofences').flush('boom', { status: 500, statusText: 'Server Error' });

    expect(store.error()).toBe('No se pudieron cargar las geocercas');
  });

  it('create() POSTs the request body as JSON and upserts the parsed response into the store', () => {
    const body: GeofenceRequest = {
      name: 'Depot',
      rule: 'ON_ENTER',
      shape: 'POLYGON',
      vertices: [{ lat: 1, lon: 1 }, { lat: 2, lon: 2 }, { lat: 3, lon: 1 }],
    };
    let result: GeofenceResponse | undefined;

    service.create(body).subscribe((response) => (result = response));

    const request = httpMock.expectOne('http://localhost:8099/api/geofences');
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual(body);
    expect(request.request.responseType).toBe('json');

    const response: GeofenceResponse = { id: 'g1', name: body.name, rule: body.rule };
    request.flush(response);

    expect(result).toEqual(response);
    expect(store.geofences()).toEqual([response]);
  });

  it('update() PUTs to the geofence id and upserts the parsed response into the store', () => {
    store.setGeofences([{ id: 'g1', name: 'Old name' }]);
    const body: GeofenceRequest = {
      name: 'New name',
      rule: 'ON_EXIT',
      shape: 'CIRCLE',
      center: { lat: 1, lon: 1 },
      radiusMeters: 50,
    };
    let result: GeofenceResponse | undefined;

    service.update('g1', body).subscribe((response) => (result = response));

    const request = httpMock.expectOne('http://localhost:8099/api/geofences/g1');
    expect(request.request.method).toBe('PUT');
    expect(request.request.body).toEqual(body);

    const response: GeofenceResponse = { id: 'g1', name: body.name, rule: body.rule };
    request.flush(response);

    expect(result).toEqual(response);
    expect(store.geofences()).toEqual([response]);
  });

  it('delete() sends a credentialed DELETE with responseType "json" and removes the geofence from the store on 204', () => {
    store.setGeofences([{ id: 'g1', name: 'Depot' }, { id: 'g2', name: 'Port' }]);
    let completed = false;

    service.delete('g1').subscribe({ complete: () => (completed = true) });

    const request = httpMock.expectOne('http://localhost:8099/api/geofences/g1');
    expect(request.request.method).toBe('DELETE');
    expect(request.request.withCredentials).toBe(true);
    expect(request.request.responseType).toBe('json');
    expect(store.geofences().length).toBe(2);

    request.flush(null, { status: 204, statusText: 'No Content' });

    expect(completed).toBe(true);
    expect(store.geofences()).toEqual([{ id: 'g2', name: 'Port' }]);
  });

  it('delete() keeps the geofence in the store and surfaces the error when the request fails', () => {
    store.setGeofences([{ id: 'g1', name: 'Depot' }]);
    let failed = false;

    service.delete('g1').subscribe({ error: () => (failed = true) });

    httpMock.expectOne('http://localhost:8099/api/geofences/g1').flush('nope', { status: 403, statusText: 'Forbidden' });

    expect(failed).toBe(true);
    expect(store.geofences()).toEqual([{ id: 'g1', name: 'Depot' }]);
  });
});

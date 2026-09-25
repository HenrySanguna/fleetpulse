import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import type { AlertResponse } from '@fleetpulse/api-client';
import { AlertsControllerService } from '@fleetpulse/api-client';
import type { Alert } from '../models/alert.model';
import { AlertsService } from './alerts.service';

// Same real-HttpClient-+-testing-backend recipe as GeofenceService's own
// spec: exercises toAlert() (the actual AlertResponse -> Alert mapping),
// something a test that mocks AlertsService itself (AlertsStore's own spec)
// never reaches.
describe('AlertsService', () => {
  let service: AlertsService;
  let httpMock: HttpTestingController;

  const baseResponse: AlertResponse = {
    id: 'a1',
    vehicleId: 'VH-1042',
    vehicleLabel: 'Camión 04',
    alertType: 'speeding',
    occurredAt: '2026-01-01T10:42:00.000Z',
    acknowledged: false,
  };

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: AlertsControllerService,
          useValue: { configuration: { basePath: 'http://localhost:8099', withCredentials: true } },
        },
      ],
    });

    service = TestBed.inject(AlertsService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('list() maps acknowledgedAt/acknowledgedBy to null when the response omits them', () => {
    let result: Alert[] | undefined;
    service.list().subscribe((alerts) => (result = alerts));

    httpMock.expectOne('http://localhost:8099/api/alerts').flush([baseResponse]);

    expect(result?.[0].acknowledgedAt).toBeNull();
    expect(result?.[0].acknowledgedBy).toBeNull();
  });

  it('acknowledge() PATCHes the alert id and maps a populated acknowledgedAt/acknowledgedBy through', () => {
    const response: AlertResponse = {
      ...baseResponse,
      acknowledged: true,
      acknowledgedAt: '2026-01-02T09:00:00.000Z',
      acknowledgedBy: 'dispatcher@acme.test',
    };
    let result: Alert | undefined;

    service.acknowledge('a1').subscribe((alert) => (result = alert));

    const request = httpMock.expectOne('http://localhost:8099/api/alerts/a1/acknowledge');
    expect(request.request.method).toBe('PATCH');
    request.flush(response);

    expect(result?.acknowledged).toBe(true);
    expect(result?.acknowledgedAt).toBe('2026-01-02T09:00:00.000Z');
    expect(result?.acknowledgedBy).toBe('dispatcher@acme.test');
  });
});

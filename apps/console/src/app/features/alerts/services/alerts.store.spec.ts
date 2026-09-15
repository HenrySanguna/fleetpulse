import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import type { Alert } from '../models/alert.model';
import { AlertsService } from './alerts.service';
import { AlertsStore } from './alerts.store';

describe('AlertsStore', () => {
  let alertsService: { list: ReturnType<typeof vi.fn> };
  let store: InstanceType<typeof AlertsStore>;

  const alerts: Alert[] = [
    {
      id: 'a1',
      vehicleId: 'VH-1042',
      vehicleLabel: 'Camión 04',
      type: 'geofence_enter',
      detail: 'Entró en la geocerca "Puerto de Valencia"',
      occurredAt: '2026-01-01T10:42:00.000Z',
      acknowledged: false,
    },
    {
      id: 'a2',
      vehicleId: 'VH-0892',
      vehicleLabel: 'Furgoneta 02',
      type: 'speeding',
      detail: '92 km/h en una zona con límite de 60 km/h',
      occurredAt: '2026-01-01T10:31:00.000Z',
      acknowledged: false,
    },
    {
      id: 'a3',
      vehicleId: 'VH-0905',
      vehicleLabel: 'Furgoneta 05',
      type: 'geofence_exit',
      detail: 'Salió de la geocerca "Depósito Norte"',
      occurredAt: '2025-12-31T08:15:00.000Z',
      acknowledged: true,
    },
  ];

  beforeEach(() => {
    alertsService = { list: vi.fn() };
    TestBed.configureTestingModule({
      providers: [{ provide: AlertsService, useValue: alertsService }],
    });
    store = TestBed.inject(AlertsStore);
  });

  it('starts empty, not loading, with no error, no filter and no search query', () => {
    expect(store.alerts()).toEqual([]);
    expect(store.loading()).toBe(false);
    expect(store.error()).toBeUndefined();
    expect(store.typeFilter()).toBe('all');
    expect(store.searchQuery()).toBe('');
  });

  it('load() calls the service once and stores the resolved alerts', () => {
    alertsService.list.mockReturnValue(of(alerts));

    store.load();

    expect(alertsService.list).toHaveBeenCalledTimes(1);
    expect(store.alerts()).toEqual(alerts);
    expect(store.loading()).toBe(false);
    expect(store.error()).toBeUndefined();
  });

  it('load() records an error and clears loading when the service errors', () => {
    alertsService.list.mockReturnValue(throwError(() => new Error('boom')));

    store.load();

    expect(store.loading()).toBe(false);
    expect(store.error()).toBe('No se pudieron cargar las alertas');
  });

  it('unacknowledgedCount reflects only the alerts that are not acknowledged', () => {
    alertsService.list.mockReturnValue(of(alerts));
    store.load();

    expect(store.unacknowledgedCount()).toBe(2);
  });

  describe('filteredAlerts', () => {
    beforeEach(() => {
      alertsService.list.mockReturnValue(of(alerts));
      store.load();
    });

    it('returns every alert when the filter is "all" and the search query is empty', () => {
      expect(store.filteredAlerts().map((a) => a.id)).toEqual(['a1', 'a2', 'a3']);
    });

    it('setTypeFilter("geofence") matches both geofence_enter and geofence_exit', () => {
      store.setTypeFilter('geofence');

      expect(store.filteredAlerts().map((a) => a.id)).toEqual(['a1', 'a3']);
    });

    it('setTypeFilter("speeding") narrows to that exact type', () => {
      store.setTypeFilter('speeding');

      expect(store.filteredAlerts().map((a) => a.id)).toEqual(['a2']);
    });

    it('setSearchQuery matches by vehicle label or vehicle id, case-insensitively', () => {
      store.setSearchQuery('furgoneta');
      expect(store.filteredAlerts().map((a) => a.id)).toEqual(['a2', 'a3']);

      store.setSearchQuery('vh-1042');
      expect(store.filteredAlerts().map((a) => a.id)).toEqual(['a1']);
    });

    it('combines the type filter and the search query', () => {
      store.setTypeFilter('geofence');
      store.setSearchQuery('05');

      expect(store.filteredAlerts().map((a) => a.id)).toEqual(['a3']);
    });
  });
});

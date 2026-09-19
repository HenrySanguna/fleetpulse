import { TestBed } from '@angular/core/testing';
import { Subject, of, throwError } from 'rxjs';
import type { ActivityReport, ActivityVehicleOption } from '../models/activity-report.model';
import { ActivityReportService } from './activity-report.service';
import { ActivityReportStore } from './activity-report.store';

describe('ActivityReportStore', () => {
  let activityReportService: { listVehicles: ReturnType<typeof vi.fn>; getReport: ReturnType<typeof vi.fn> };
  let store: InstanceType<typeof ActivityReportStore>;

  const vehicles: ActivityVehicleOption[] = [
    { id: 'VH-1042', label: 'Camión 04' },
    { id: 'VH-0892', label: 'Furgoneta 02' },
  ];

  const reportByVehicle: Record<string, ActivityReport> = {
    'VH-1042': {
      vehicleId: 'VH-1042',
      summary: { totalDistanceKm: 428.6, movingMinutes: 684, idleMinutes: 112, avgSpeedKmh: 54, maxSpeedKmh: 97 },
      dailyDistances: [{ day: '2026-09-14', distanceKm: 52 }],
      trips: [
        {
          id: 't-1042-1',
          startedAt: '2026-09-14T07:02:00Z',
          endedAt: '2026-09-14T09:18:00Z',
          distanceKm: 61.2,
          durationMinutes: 136,
          idleMinutes: 8,
          maxSpeedKmh: 94,
        },
      ],
    },
    'VH-0892': {
      vehicleId: 'VH-0892',
      summary: { totalDistanceKm: 156.4, movingMinutes: 402, idleMinutes: 168, avgSpeedKmh: 33, maxSpeedKmh: 71 },
      dailyDistances: [{ day: '2026-09-14', distanceKm: 18.2 }],
      trips: [
        {
          id: 't-0892-1',
          startedAt: '2026-09-14T08:10:00Z',
          endedAt: '2026-09-14T09:40:00Z',
          distanceKm: 14.6,
          durationMinutes: 90,
          idleMinutes: 22,
          maxSpeedKmh: 58,
        },
      ],
    },
  };

  beforeEach(() => {
    activityReportService = { listVehicles: vi.fn(), getReport: vi.fn() };
    TestBed.configureTestingModule({
      providers: [{ provide: ActivityReportService, useValue: activityReportService }],
    });
    store = TestBed.inject(ActivityReportStore);
  });

  it('starts empty, not loading, with no error and no selected vehicle', () => {
    expect(store.vehicles()).toEqual([]);
    expect(store.selectedVehicleId()).toBeUndefined();
    expect(store.loading()).toBe(false);
    expect(store.error()).toBeUndefined();
    expect(store.summary()).toBeUndefined();
    expect(store.dailyDistances()).toEqual([]);
    expect(store.trips()).toEqual([]);
  });

  it('loadVehicles() stores the vehicle list and auto-selects and loads the first vehicle', () => {
    activityReportService.listVehicles.mockReturnValue(of(vehicles));
    activityReportService.getReport.mockReturnValue(of(reportByVehicle['VH-1042']));

    store.loadVehicles();

    expect(activityReportService.listVehicles).toHaveBeenCalledTimes(1);
    expect(store.vehicles()).toEqual(vehicles);
    expect(store.selectedVehicleId()).toBe('VH-1042');
    expect(activityReportService.getReport).toHaveBeenCalledWith('VH-1042', expect.any(String), expect.any(String));
    expect(store.summary()).toEqual(reportByVehicle['VH-1042'].summary);
  });

  it('selectVehicle() switches the selected id and loads that vehicle\'s report', () => {
    activityReportService.listVehicles.mockReturnValue(of(vehicles));
    activityReportService.getReport.mockImplementation((vehicleId: string) => of(reportByVehicle[vehicleId]));
    store.loadVehicles();

    store.selectVehicle('VH-0892');

    expect(store.selectedVehicleId()).toBe('VH-0892');
    expect(activityReportService.getReport).toHaveBeenLastCalledWith('VH-0892', expect.any(String), expect.any(String));
    expect(store.summary()).toEqual(reportByVehicle['VH-0892'].summary);
    expect(store.dailyDistances()).toEqual(reportByVehicle['VH-0892'].dailyDistances);
    expect(store.trips()).toEqual(reportByVehicle['VH-0892'].trips);
  });

  it('loadReport() records an error and clears loading when the service errors', () => {
    activityReportService.listVehicles.mockReturnValue(of(vehicles));
    activityReportService.getReport.mockReturnValue(throwError(() => new Error('boom')));

    store.loadVehicles();

    expect(store.loading()).toBe(false);
    expect(store.error()).toBe('No se pudo generar el informe de actividad');
  });

  it('loadReport() does nothing when no vehicle is selected', () => {
    store.loadReport();

    expect(activityReportService.getReport).not.toHaveBeenCalled();
  });

  // Regression: loadVehicles() used to only auto-select+load a report the
  // very first time (`!store.selectedVehicleId()`), so revisiting the page
  // re-fetched the vehicle list but silently kept showing a stale report.
  it('loadVehicles() reloads the already-selected vehicle\'s report on a second call', () => {
    activityReportService.listVehicles.mockReturnValue(of(vehicles));
    activityReportService.getReport.mockImplementation((vehicleId: string) => of(reportByVehicle[vehicleId]));
    store.loadVehicles();
    expect(activityReportService.getReport).toHaveBeenCalledTimes(1);

    store.loadVehicles();

    expect(store.selectedVehicleId()).toBe('VH-1042');
    expect(activityReportService.getReport).toHaveBeenCalledTimes(2);
  });

  // Regression: selectVehicle()/loadReport() used to subscribe per call with
  // no cancellation, so an out-of-order response could overwrite the
  // already-rendered, more recent selection with stale data.
  it('ignores a stale, out-of-order response from a superseded selectVehicle() call', () => {
    activityReportService.listVehicles.mockReturnValue(of(vehicles));
    const firstRequest = new Subject<ActivityReport>();
    const secondRequest = new Subject<ActivityReport>();
    activityReportService.getReport.mockReturnValueOnce(of(reportByVehicle['VH-1042'])).mockReturnValueOnce(firstRequest).mockReturnValueOnce(secondRequest);
    store.loadVehicles();

    store.selectVehicle('VH-0892');
    store.selectVehicle('VH-1042');
    // The second (newer) request resolves first; the first (now-stale) one
    // resolves after it.
    secondRequest.next(reportByVehicle['VH-1042']);
    firstRequest.next(reportByVehicle['VH-0892']);

    expect(store.selectedVehicleId()).toBe('VH-1042');
    expect(store.summary()).toEqual(reportByVehicle['VH-1042'].summary);
  });

  it('reset() restores the initial state', () => {
    activityReportService.listVehicles.mockReturnValue(of(vehicles));
    activityReportService.getReport.mockReturnValue(of(reportByVehicle['VH-1042']));
    store.loadVehicles();

    store.reset();

    expect(store.vehicles()).toEqual([]);
    expect(store.selectedVehicleId()).toBeUndefined();
    expect(store.report()).toBeUndefined();
  });
});

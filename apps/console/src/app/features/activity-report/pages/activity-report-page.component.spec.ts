import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import type { ActivityReport, ActivityVehicleOption } from '../models/activity-report.model';
import { ActivityReportService } from '../services/activity-report.service';
import { ActivityReportPageComponent } from './activity-report-page.component';

const VEHICLES: ActivityVehicleOption[] = [
  { id: 'VH-1042', label: 'Camión 04' },
  { id: 'VH-0892', label: 'Furgoneta 02' },
];

const REPORTS: Record<string, ActivityReport> = {
  'VH-1042': {
    vehicleId: 'VH-1042',
    summary: { totalDistanceKm: 428.6, movingMinutes: 684, idleMinutes: 112, avgSpeedKmh: 54, maxSpeedKmh: 97 },
    dailyDistances: [{ day: 'Lun', distanceKm: 52 }],
    trips: [
      { date: '14/09', startTime: '07:02', endTime: '09:18', distanceKm: 61.2, durationMinutes: 136, idleMinutes: 8, maxSpeedKmh: 94 },
      { date: '13/09', startTime: '06:45', endTime: '12:30', distanceKm: 88.4, durationMinutes: 190, idleMinutes: 31, maxSpeedKmh: 97 },
    ],
  },
  'VH-0892': {
    vehicleId: 'VH-0892',
    summary: { totalDistanceKm: 156.4, movingMinutes: 402, idleMinutes: 168, avgSpeedKmh: 33, maxSpeedKmh: 71 },
    dailyDistances: [{ day: 'Lun', distanceKm: 18.2 }],
    trips: [{ date: '14/09', startTime: '08:10', endTime: '09:40', distanceKm: 14.6, durationMinutes: 90, idleMinutes: 22, maxSpeedKmh: 58 }],
  },
};

describe('ActivityReportPageComponent', () => {
  let activityReportService: { listVehicles: ReturnType<typeof vi.fn>; getReport: ReturnType<typeof vi.fn> };

  beforeEach(() => {
    activityReportService = {
      listVehicles: vi.fn(() => of(VEHICLES)),
      getReport: vi.fn((vehicleId: string) => of(REPORTS[vehicleId])),
    };
    TestBed.configureTestingModule({
      providers: [{ provide: ActivityReportService, useValue: activityReportService }],
    });
  });

  function createFixture() {
    const fixture = TestBed.createComponent(ActivityReportPageComponent);
    fixture.detectChanges();
    return fixture;
  }

  function statValue(fixture: ReturnType<typeof createFixture>, testId: string): string {
    const el: HTMLElement = fixture.nativeElement.querySelector(`[data-testid="${testId}"] .stat-value`);
    return el.textContent?.trim() ?? '';
  }

  it('loads the vehicle list and defaults to the first vehicle report', () => {
    const fixture = createFixture();

    expect(activityReportService.listVehicles).toHaveBeenCalledTimes(1);
    expect(activityReportService.getReport).toHaveBeenCalledWith('VH-1042');
    expect(statValue(fixture, 'stat-distance')).toContain('428.6');
    expect(fixture.nativeElement.querySelectorAll('[data-testid="activity-trip-row"]').length).toBe(2);
  });

  it('gives the vehicle selector an accessible name', () => {
    const fixture = createFixture();

    const select: HTMLSelectElement = fixture.nativeElement.querySelector('[data-testid="activity-vehicle-select"]');
    expect(select.getAttribute('aria-label')).toBeTruthy();
  });

  it('switching the vehicle selector changes the rendered stat values', () => {
    const fixture = createFixture();

    const select: HTMLSelectElement = fixture.nativeElement.querySelector('[data-testid="activity-vehicle-select"]');
    select.value = 'VH-0892';
    select.dispatchEvent(new Event('change'));
    fixture.detectChanges();

    expect(activityReportService.getReport).toHaveBeenLastCalledWith('VH-0892');
    expect(statValue(fixture, 'stat-distance')).toContain('156.4');
    expect(statValue(fixture, 'stat-moving')).toContain('6h 42');
    expect(statValue(fixture, 'stat-idle')).toContain('2h 48');
    expect(statValue(fixture, 'stat-speed')).toContain('33 / 71');
    expect(fixture.nativeElement.querySelectorAll('[data-testid="activity-trip-row"]').length).toBe(1);
  });

  it('includes the month on both ends of the date-range label when the 7-day window crosses a month boundary', () => {
    // Sept 3 minus 6 days lands in August -- a bare "28" for the start
    // would misleadingly read as August 28th being inside September.
    vi.useFakeTimers();
    vi.setSystemTime(new Date(2026, 8, 3)); // month is 0-indexed: 8 = September
    const fixture = createFixture();

    const label: HTMLElement = fixture.nativeElement.querySelector('.date-display');
    expect(label.textContent).toMatch(/ago\.?.*–.*sept\.?/);

    vi.useRealTimers();
  });

  it('clicking "Generar informe" re-triggers loading the selected vehicle\'s report', () => {
    const fixture = createFixture();
    activityReportService.getReport.mockClear();

    const button: HTMLButtonElement = fixture.nativeElement.querySelector('[data-testid="activity-generate-report"]');
    button.click();
    fixture.detectChanges();

    expect(activityReportService.getReport).toHaveBeenCalledWith('VH-1042');
  });
});

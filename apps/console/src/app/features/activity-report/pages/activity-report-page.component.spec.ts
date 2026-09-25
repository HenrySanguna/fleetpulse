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
    // Prod QA fix: avgSpeedKmh here mirrors the actual production bug report
    // (a raw float average, e.g. "44.36987553618879") -- kept as a decimal
    // deliberately so a regression back to raw interpolation fails a test.
    summary: { totalDistanceKm: 428.6, movingMinutes: 684, idleMinutes: 112, avgSpeedKmh: 44.36987553618879, maxSpeedKmh: 97 },
    dailyDistances: [{ day: '2026-09-14', distanceKm: 52 }],
    trips: [
      {
        id: 't-1042-1',
        startedAt: '2026-09-14T07:02:00Z',
        endedAt: '2026-09-14T09:18:00Z',
        distanceKm: 61.2,
        durationMinutes: 136,
        idleMinutes: 8,
        maxSpeedKmh: 94.7,
      },
      {
        id: 't-1042-2',
        startedAt: '2026-09-13T06:45:00Z',
        endedAt: '2026-09-13T12:30:00Z',
        distanceKm: 88.4,
        durationMinutes: 190,
        idleMinutes: 31,
        maxSpeedKmh: 97,
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
    expect(activityReportService.getReport).toHaveBeenCalledWith('VH-1042', expect.any(String), expect.any(String));
    expect(statValue(fixture, 'stat-distance')).toContain('428.6');
    expect(fixture.nativeElement.querySelectorAll('[data-testid="activity-trip-row"]').length).toBe(2);
  });

  // Task 4.3: trips now carry raw ISO-8601 startedAt/endedAt (not the mock's
  // pre-formatted date/startTime/endTime strings) -- asserts on SHAPE
  // (DD/MM, HH:MM), not an exact clock value, since the exact wall-clock
  // rendering of a UTC instant depends on the machine's local timezone.
  it('formats each trip row\'s date and times from its raw ISO instants', () => {
    const fixture = createFixture();

    const row: HTMLElement = fixture.nativeElement.querySelector('[data-testid="activity-trip-row"]');
    const cells = row.querySelectorAll('td');
    expect(cells[0].textContent).toMatch(/^\d{2}\/\d{2}$/);
    expect(cells[1].textContent).toMatch(/^\d{2}:\d{2}$/);
    expect(cells[2].textContent).toMatch(/^\d{2}:\d{2}$/);
  });

  // Task 4.4: DailyDistancePoint.day is now a raw ISO calendar date -- this
  // proves the chart renders a real weekday label derived from it, not the
  // raw "2026-09-14" string itself.
  it('renders a weekday abbreviation for each daily-distance chart bar, not the raw ISO date', () => {
    const fixture = createFixture();

    const bar: HTMLElement = fixture.nativeElement.querySelector('.bar-day');
    expect(bar.textContent?.trim()).not.toBe('');
    expect(bar.textContent).not.toContain('2026-09-14');
  });

  // Prod QA fix: average speed is a genuine backend float
  // (44.36987553618879) -- both summary speeds and each trip row's max speed
  // must render as rounded whole km/h, never the raw decimal.
  it('formats average/max speed and each trip\'s max speed as rounded integers, never a raw float', () => {
    const fixture = createFixture();

    expect(statValue(fixture, 'stat-speed')).toContain('44 / 97');
    const row: HTMLElement = fixture.nativeElement.querySelector('[data-testid="activity-trip-row"]');
    const cells = row.querySelectorAll('td');
    expect(cells[6].textContent?.trim()).toBe('95 km/h');
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

    expect(activityReportService.getReport).toHaveBeenLastCalledWith('VH-0892', expect.any(String), expect.any(String));
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

    expect(activityReportService.getReport).toHaveBeenCalledWith('VH-1042', expect.any(String), expect.any(String));
  });
});

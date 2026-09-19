import { ChangeDetectionStrategy, Component, type OnInit, computed, inject } from '@angular/core';
import type { ActivityTripRow, DailyDistancePoint } from '../models/activity-report.model';
import { defaultActivityReportRange } from '../services/activity-report-range';
import { ActivityReportStore } from '../services/activity-report.store';

interface ChartBar {
  readonly day: string;
  readonly distanceKm: number;
  readonly heightPx: number;
  readonly hasData: boolean;
  readonly showLabel: boolean;
}

const CHART_MAX_HEIGHT_PX = 130;
const CHART_MIN_HEIGHT_PX = 4;
// Only the tallest bars get a direct value label (mockup: "direct value
// labels on a few bars") -- this keeps the chart readable instead of
// stamping a number over all seven days.
const CHART_LABEL_THRESHOLD_RATIO = 0.75;

// Task 4.4: DailyDistancePoint.day is now a raw ISO calendar date (backend:
// DailyDistancePointResponse.day, vehicle_daily's own UTC day column), not
// the mock's pre-formatted Spanish weekday abbreviation -- this derives that
// same "Lun"/"Mar"/... label from the real date, locale-correct for any
// range rather than a fixed Mon-Sun mock week. Appending 'T00:00:00Z' keeps
// the parse anchored to the UTC calendar day the backend means, instead of
// letting the browser's local timezone shift it onto the neighboring day.
function formatChartDayLabel(isoDay: string): string {
  return new Date(`${isoDay}T00:00:00Z`).toLocaleDateString('es-ES', { weekday: 'short', timeZone: 'UTC' }).replace('.', '');
}

function buildChartBars(points: readonly DailyDistancePoint[]): ChartBar[] {
  const maxDistance = Math.max(0, ...points.map((point) => point.distanceKm));
  return points.map((point) => {
    const hasData = point.distanceKm > 0;
    const heightPx =
      hasData && maxDistance > 0
        ? Math.max(CHART_MIN_HEIGHT_PX, Math.round((point.distanceKm / maxDistance) * CHART_MAX_HEIGHT_PX))
        : CHART_MIN_HEIGHT_PX;
    return {
      day: formatChartDayLabel(point.day),
      distanceKm: point.distanceKm,
      heightPx,
      hasData,
      showLabel: hasData && point.distanceKm >= maxDistance * CHART_LABEL_THRESHOLD_RATIO,
    };
  });
}

function formatHoursMinutes(totalMinutes: number): string {
  const hours = Math.floor(totalMinutes / 60);
  const minutes = totalMinutes % 60;
  return `${hours}h ${String(minutes).padStart(2, '0')}`;
}

// Presentational-only, computed relative to "now" (like AlertsService's
// todayAt/yesterdayAt) so it never goes stale -- not wired to any actual
// filtering, see the page's own doc comment. Built from the SAME
// defaultActivityReportRange() the store uses to build its real HTTP
// request, so this label can never silently drift from the range the
// backend actually queried.
function buildDateRangeLabel(): string {
  const { from: start, to: end } = defaultActivityReportRange();
  const endLabel = end.toLocaleDateString('es-ES', { day: 'numeric', month: 'short', year: 'numeric' });
  // A bare day-of-month for `start` (e.g. "28 – 3 sept 2026") reads as if
  // both dates share September when the 7-day window crosses a month
  // boundary -- only safe to drop the month when start and end are
  // actually in the same one.
  const sameMonth = start.getMonth() === end.getMonth() && start.getFullYear() === end.getFullYear();
  const startLabel = sameMonth ? String(start.getDate()) : start.toLocaleDateString('es-ES', { day: 'numeric', month: 'short' });
  return `${startLabel} – ${endLabel}`;
}

// Task 4.3: ActivityTripRow.startedAt/endedAt are now raw ISO-8601 instants
// (backend: ActivityTripResponse), not the mock's pre-formatted date/
// startTime/endTime strings -- same "raw data in, format at the
// presentation layer" split AlertsPageComponent's own formatTime() already
// established for occurredAt. Manual padStart (not Intl's `2-digit` option)
// -- Intl's own "2-digit" numeric formatting is not reliably zero-padded
// across this project's actual Node/ICU runtimes, the same manual
// padStart() approach AlertsPageComponent's own dayKey() already uses for
// exactly this reason.
function formatShortDate(iso: string): string {
  const date = new Date(iso);
  const day = String(date.getDate()).padStart(2, '0');
  const month = String(date.getMonth() + 1).padStart(2, '0');
  return `${day}/${month}`;
}

function formatShortTime(iso: string): string {
  const date = new Date(iso);
  const hours = String(date.getHours()).padStart(2, '0');
  const minutes = String(date.getMinutes()).padStart(2, '0');
  return `${hours}:${minutes}`;
}

@Component({
  selector: 'app-activity-report-page',
  imports: [],
  templateUrl: './activity-report-page.component.html',
  styleUrl: './activity-report-page.component.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ActivityReportPageComponent implements OnInit {
  private readonly store = inject(ActivityReportStore);

  protected readonly vehicles = this.store.vehicles;
  protected readonly selectedVehicleId = this.store.selectedVehicleId;
  protected readonly loading = this.store.loading;
  protected readonly loadError = this.store.error;
  protected readonly trips = this.store.trips;

  // Static display only -- the date-range control is not wired to any
  // filtering yet (scope-bounded per this screen's task: only the vehicle
  // selector needs to actually switch the rendered dataset).
  protected readonly dateRangeLabel = buildDateRangeLabel();

  protected readonly chartBars = computed(() => buildChartBars(this.store.dailyDistances()));

  protected readonly distanceLabel = computed(() => {
    const summary = this.store.summary();
    return summary ? summary.totalDistanceKm.toFixed(1) : '--';
  });

  protected readonly movingLabel = computed(() => {
    const summary = this.store.summary();
    return summary ? formatHoursMinutes(summary.movingMinutes) : '--';
  });

  protected readonly idleLabel = computed(() => {
    const summary = this.store.summary();
    return summary ? formatHoursMinutes(summary.idleMinutes) : '--';
  });

  protected readonly speedLabel = computed(() => {
    const summary = this.store.summary();
    return summary ? `${summary.avgSpeedKmh} / ${summary.maxSpeedKmh}` : '--';
  });

  ngOnInit(): void {
    this.store.loadVehicles();
  }

  protected onVehicleChange(event: Event): void {
    const vehicleId = (event.target as HTMLSelectElement).value;
    this.store.selectVehicle(vehicleId);
  }

  protected generateReport(): void {
    this.store.loadReport();
  }

  protected formatTripDuration(trip: ActivityTripRow): string {
    return `${formatHoursMinutes(trip.durationMinutes)}m`;
  }

  protected formatTripIdle(trip: ActivityTripRow): string {
    return `${formatHoursMinutes(trip.idleMinutes)}m`;
  }

  protected formatTripDate(trip: ActivityTripRow): string {
    return formatShortDate(trip.startedAt);
  }

  protected formatTripStart(trip: ActivityTripRow): string {
    return formatShortTime(trip.startedAt);
  }

  protected formatTripEnd(trip: ActivityTripRow): string {
    return formatShortTime(trip.endedAt);
  }
}

import { ChangeDetectionStrategy, Component, type OnInit, computed, inject } from '@angular/core';
import type { ActivityTripRow, DailyDistancePoint } from '../models/activity-report.model';
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

function buildChartBars(points: readonly DailyDistancePoint[]): ChartBar[] {
  const maxDistance = Math.max(0, ...points.map((point) => point.distanceKm));
  return points.map((point) => {
    const hasData = point.distanceKm > 0;
    const heightPx =
      hasData && maxDistance > 0
        ? Math.max(CHART_MIN_HEIGHT_PX, Math.round((point.distanceKm / maxDistance) * CHART_MAX_HEIGHT_PX))
        : CHART_MIN_HEIGHT_PX;
    return {
      day: point.day,
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
// filtering, see the page's own doc comment.
function buildDateRangeLabel(): string {
  const end = new Date();
  const start = new Date(end);
  start.setDate(end.getDate() - 6);
  const endLabel = end.toLocaleDateString('es-ES', { day: 'numeric', month: 'short', year: 'numeric' });
  // A bare day-of-month for `start` (e.g. "28 – 3 sept 2026") reads as if
  // both dates share September when the 7-day window crosses a month
  // boundary -- only safe to drop the month when start and end are
  // actually in the same one.
  const sameMonth = start.getMonth() === end.getMonth() && start.getFullYear() === end.getFullYear();
  const startLabel = sameMonth ? String(start.getDate()) : start.toLocaleDateString('es-ES', { day: 'numeric', month: 'short' });
  return `${startLabel} – ${endLabel}`;
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
}

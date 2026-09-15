import { ChangeDetectionStrategy, Component, type OnInit, computed, inject } from '@angular/core';
import type { Alert, AlertTypeFilter } from '../models/alert.model';
import { AlertsStore } from '../services/alerts.store';

export type AlertSeverity = 'success' | 'warning' | 'danger' | 'neutral';

const SEVERITY_BY_TYPE: Record<Alert['type'], AlertSeverity> = {
  geofence_enter: 'success',
  geofence_exit: 'neutral',
  speeding: 'danger',
  excessive_idle: 'warning',
  offline: 'danger',
};

interface FilterChip {
  readonly value: AlertTypeFilter;
  readonly label: string;
}

const FILTER_CHIPS: readonly FilterChip[] = [
  { value: 'all', label: 'Todas' },
  { value: 'geofence', label: 'Geocerca' },
  { value: 'speeding', label: 'Exceso de velocidad' },
  { value: 'excessive_idle', label: 'Ralentí excesivo' },
  { value: 'offline', label: 'Sin conexión' },
];

interface AlertDayGroup {
  readonly key: string;
  readonly label: string;
  readonly alerts: readonly Alert[];
}

function dayKey(iso: string): string {
  const date = new Date(iso);
  const month = String(date.getMonth() + 1).padStart(2, '0');
  const day = String(date.getDate()).padStart(2, '0');
  return `${date.getFullYear()}-${month}-${day}`;
}

function dayLabel(iso: string): string {
  const today = new Date();
  const yesterday = new Date(today);
  yesterday.setDate(yesterday.getDate() - 1);

  const key = dayKey(iso);
  if (key === dayKey(today.toISOString())) {
    return 'Hoy';
  }
  if (key === dayKey(yesterday.toISOString())) {
    return 'Ayer';
  }
  return new Date(iso).toLocaleDateString('es-ES', { day: 'numeric', month: 'short' });
}

// Groups filteredAlerts() by calendar day client-side (kept simpler than the
// mockup's own grouping logic by not needing a server-side "day bucket" --
// this is the only place that reasons about calendar days at all).
function groupByDay(alerts: readonly Alert[]): AlertDayGroup[] {
  const groups = new Map<string, Alert[]>();
  for (const alert of alerts) {
    const key = dayKey(alert.occurredAt);
    const bucket = groups.get(key);
    if (bucket) {
      bucket.push(alert);
    } else {
      groups.set(key, [alert]);
    }
  }
  return [...groups.entries()]
    .sort(([a], [b]) => (a < b ? 1 : -1))
    .map(([key, dayAlerts]) => ({
      key,
      label: dayLabel(dayAlerts[0].occurredAt),
      // Newest-first within the day -- today's mock data happens to already
      // arrive that way, but nothing enforced it, so a real backend
      // response in any other order would render same-day cards scrambled.
      alerts: [...dayAlerts].sort((a, b) => Date.parse(b.occurredAt) - Date.parse(a.occurredAt)),
    }));
}

@Component({
  selector: 'app-alerts-page',
  imports: [],
  templateUrl: './alerts-page.component.html',
  styleUrl: './alerts-page.component.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AlertsPageComponent implements OnInit {
  private readonly store = inject(AlertsStore);

  protected readonly filterChips = FILTER_CHIPS;
  protected readonly typeFilter = this.store.typeFilter;
  protected readonly loading = this.store.loading;
  protected readonly loadError = this.store.error;
  protected readonly totalCount = computed(() => this.store.alerts().length);
  protected readonly unacknowledgedCount = this.store.unacknowledgedCount;
  protected readonly dayGroups = computed(() => groupByDay(this.store.filteredAlerts()));

  ngOnInit(): void {
    this.store.load();
  }

  protected setTypeFilter(value: AlertTypeFilter): void {
    this.store.setTypeFilter(value);
  }

  protected onSearchInput(event: Event): void {
    this.store.setSearchQuery((event.target as HTMLInputElement).value);
  }

  protected severityOf(type: Alert['type']): AlertSeverity {
    return SEVERITY_BY_TYPE[type];
  }

  protected formatTime(iso: string): string {
    return new Date(iso).toLocaleTimeString('es-ES', { hour: '2-digit', minute: '2-digit' });
  }
}

import { computed, inject } from '@angular/core';
import { patchState, signalStore, withComputed, withMethods, withState } from '@ngrx/signals';
import type { Alert, AlertTypeFilter } from '../models/alert.model';
import { AlertsService } from './alerts.service';

interface AlertsStoreState {
  readonly alerts: readonly Alert[];
  readonly loading: boolean;
  readonly error: string | undefined;
  readonly typeFilter: AlertTypeFilter;
  readonly searchQuery: string;
}

const INITIAL_STATE: AlertsStoreState = {
  alerts: [],
  loading: false,
  error: undefined,
  typeFilter: 'all',
  searchQuery: '',
};

function matchesTypeFilter(alert: Alert, filter: AlertTypeFilter): boolean {
  if (filter === 'all') {
    return true;
  }
  if (filter === 'geofence') {
    return alert.type === 'geofence_enter' || alert.type === 'geofence_exit';
  }
  return alert.type === filter;
}

// Pure state container, same split as GeofenceStore -- except `load()` lives
// here rather than on a separate orchestration service. There's no
// create/update to keep out of the store's way (unlike GeofenceStore), so
// the extra layer would just forward one call; this follows AuthStore's own
// documented deviation (store injects its service directly) instead.
export const AlertsStore = signalStore(
  { providedIn: 'root' },
  withState<AlertsStoreState>(INITIAL_STATE),
  withComputed(({ alerts, typeFilter, searchQuery }) => ({
    filteredAlerts: computed(() => {
      const query = searchQuery().trim().toLowerCase();
      return alerts()
        .filter((alert) => matchesTypeFilter(alert, typeFilter()))
        .filter(
          (alert) => !query || alert.vehicleLabel.toLowerCase().includes(query) || alert.vehicleId.toLowerCase().includes(query),
        );
    }),
    unacknowledgedCount: computed(() => alerts().filter((alert) => !alert.acknowledged).length),
  })),
  withMethods((store) => {
    const alertsService = inject(AlertsService);

    return {
      load(): void {
        patchState(store, { loading: true, error: undefined });
        alertsService.list().subscribe({
          next: (alerts) => patchState(store, { alerts, loading: false }),
          error: (error: unknown) => {
            console.error('AlertsStore: failed to load alerts', error);
            patchState(store, { loading: false, error: 'No se pudieron cargar las alertas' });
          },
        });
      },

      setTypeFilter(typeFilter: AlertTypeFilter): void {
        patchState(store, { typeFilter });
      },

      setSearchQuery(searchQuery: string): void {
        patchState(store, { searchQuery });
      },

      reset(): void {
        patchState(store, INITIAL_STATE);
      },
    };
  }),
);

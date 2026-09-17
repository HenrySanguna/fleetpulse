import { computed, inject } from '@angular/core';
import { patchState, signalStore, withComputed, withMethods, withState } from '@ngrx/signals';
import type { ActivityReport, ActivityVehicleOption } from '../models/activity-report.model';
import { defaultActivityReportRange } from './activity-report-range';
import { ActivityReportService } from './activity-report.service';

interface ActivityReportStoreState {
  readonly vehicles: readonly ActivityVehicleOption[];
  readonly selectedVehicleId: string | undefined;
  readonly report: ActivityReport | undefined;
  readonly loading: boolean;
  readonly error: string | undefined;
}

const INITIAL_STATE: ActivityReportStoreState = {
  vehicles: [],
  selectedVehicleId: undefined,
  report: undefined,
  loading: false,
  error: undefined,
};

// Pure state container, same split AlertsStore uses: this injects
// ActivityReportService directly rather than routing through an extra
// orchestration service, since there's no create/update flow to keep out of
// the store's way here either.
export const ActivityReportStore = signalStore(
  { providedIn: 'root' },
  withState<ActivityReportStoreState>(INITIAL_STATE),
  withComputed(({ report }) => ({
    summary: computed(() => report()?.summary),
    dailyDistances: computed(() => report()?.dailyDistances ?? []),
    trips: computed(() => report()?.trips ?? []),
  })),
  withMethods((store) => {
    const activityReportService = inject(ActivityReportService);
    // Guards against an out-of-order response: switching vehicle A -> B
    // before A's request resolves must never let A's late response
    // overwrite B's already-rendered report. Each call captures its own id
    // and only patches state if it's still the most recent call.
    let latestRequestId = 0;

    function loadReport(): void {
      const vehicleId = store.selectedVehicleId();
      if (!vehicleId) {
        return;
      }
      const requestId = ++latestRequestId;
      const range = defaultActivityReportRange();
      patchState(store, { loading: true, error: undefined });
      activityReportService.getReport(vehicleId, range.from.toISOString(), range.to.toISOString()).subscribe({
        next: (report) => {
          if (requestId === latestRequestId) {
            patchState(store, { report, loading: false });
          }
        },
        error: (error: unknown) => {
          console.error('ActivityReportStore: failed to load the activity report', error);
          if (requestId === latestRequestId) {
            patchState(store, { loading: false, error: 'No se pudo generar el informe de actividad' });
          }
        },
      });
    }

    return {
      // Always reloads the report for the resolved vehicle -- previously
      // only did so `!store.selectedVehicleId()`, so revisiting this page
      // (selectedVehicleId already set from a prior visit) refreshed the
      // vehicle list but silently kept showing the stale report.
      loadVehicles(): void {
        patchState(store, { loading: true, error: undefined });
        activityReportService.listVehicles().subscribe({
          next: (vehicles) => {
            patchState(store, { vehicles });
            const vehicleId = store.selectedVehicleId() ?? vehicles[0]?.id;
            if (vehicleId) {
              patchState(store, { selectedVehicleId: vehicleId });
              loadReport();
            } else {
              patchState(store, { loading: false });
            }
          },
          error: (error: unknown) => {
            console.error('ActivityReportStore: failed to load the vehicle list', error);
            patchState(store, { loading: false, error: 'No se pudo cargar la lista de vehículos' });
          },
        });
      },

      selectVehicle(vehicleId: string): void {
        patchState(store, { selectedVehicleId: vehicleId });
        loadReport();
      },

      loadReport,

      reset(): void {
        patchState(store, INITIAL_STATE);
      },
    };
  }),
);

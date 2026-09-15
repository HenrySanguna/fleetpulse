import { computed, inject } from '@angular/core';
import { patchState, signalStore, withComputed, withMethods, withState } from '@ngrx/signals';
import type { ActivityReport, ActivityVehicleOption } from '../models/activity-report.model';
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

    function loadReport(): void {
      const vehicleId = store.selectedVehicleId();
      if (!vehicleId) {
        return;
      }
      patchState(store, { loading: true, error: undefined });
      activityReportService.getReport(vehicleId).subscribe({
        next: (report) => patchState(store, { report, loading: false }),
        error: (error: unknown) => {
          console.error('ActivityReportStore: failed to load the activity report', error);
          patchState(store, { loading: false, error: 'No se pudo generar el informe de actividad' });
        },
      });
    }

    return {
      loadVehicles(): void {
        activityReportService.listVehicles().subscribe({
          next: (vehicles) => {
            patchState(store, { vehicles });
            const firstVehicleId = vehicles[0]?.id;
            if (firstVehicleId && !store.selectedVehicleId()) {
              patchState(store, { selectedVehicleId: firstVehicleId });
              loadReport();
            }
          },
          error: (error: unknown) => {
            console.error('ActivityReportStore: failed to load the vehicle list', error);
            patchState(store, { error: 'No se pudo cargar la lista de vehículos' });
          },
        });
      },

      selectVehicle(vehicleId: string): void {
        patchState(store, { selectedVehicleId: vehicleId });
        loadReport();
      },

      loadReport,
    };
  }),
);

import { computed } from '@angular/core';
import { patchState, signalStore, withComputed, withMethods, withState } from '@ngrx/signals';
import type { GeofenceResponse } from '@fleetpulse/api-client';

interface GeofenceStoreState {
  readonly geofences: readonly GeofenceResponse[];
  readonly loading: boolean;
  readonly error: string | undefined;
  readonly selectedId: string | undefined;
}

const INITIAL_STATE: GeofenceStoreState = {
  geofences: [],
  loading: false,
  error: undefined,
  selectedId: undefined,
};

// Tasks 5.1-5.3: pure state container for the geofencing editor, mirroring
// FleetStore's own split (task 3.1 of 04-add-live-map) -- HTTP orchestration
// lives in GeofenceService, this store only ever receives already-resolved
// data or a plain loading/error flag. Shared by the editor page (create/edit
// list) and LiveMapComponent (task 5.3's visualization layer), so both read
// the exact same fetched geofence set instead of two independent copies that
// could disagree after a create/update.
export const GeofenceStore = signalStore(
  { providedIn: 'root' },
  withState<GeofenceStoreState>(INITIAL_STATE),
  withComputed(({ geofences, selectedId }) => ({
    selected: computed(() => geofences().find((g) => g.id === selectedId())),
  })),
  withMethods((store) => ({
    setLoading(loading: boolean): void {
      patchState(store, { loading, error: loading ? undefined : store.error() });
    },

    setGeofences(geofences: readonly GeofenceResponse[]): void {
      patchState(store, { geofences, loading: false, error: undefined });
    },

    setError(error: string): void {
      patchState(store, { loading: false, error });
    },

    // A create/update response replaces the matching row if it already
    // existed, or is appended otherwise -- avoids a full list()
    // round-trip just to reflect one just-saved geofence immediately.
    upsertGeofence(geofence: GeofenceResponse): void {
      const current = store.geofences();
      const index = current.findIndex((g) => g.id === geofence.id);
      const next = index === -1 ? [...current, geofence] : current.map((g, i) => (i === index ? geofence : g));
      patchState(store, { geofences: next });
    },

    select(id: string | undefined): void {
      patchState(store, { selectedId: id });
    },
  })),
);

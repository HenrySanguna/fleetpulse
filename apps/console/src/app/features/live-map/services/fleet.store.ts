import { computed } from '@angular/core';
import { patchState, signalStore, withComputed, withMethods, withState } from '@ngrx/signals';
import type { FleetStateResponse, VehicleStateResponse } from '@fleetpulse/api-client';
import { INITIAL_FLEET_FILTERS, type FleetFilters } from '../models/fleet-filters.model';
import type { VehicleState } from '../models/vehicle-state.model';
import type { VehicleUpdate } from './fleet-message.mapper';

interface FleetStoreState {
  readonly vehicles: ReadonlyMap<string, VehicleState>;
  readonly filters: FleetFilters;
  readonly selectedVehicleId: string | undefined;
}

const INITIAL_STATE: FleetStoreState = {
  vehicles: new Map(),
  filters: INITIAL_FLEET_FILTERS,
  selectedVehicleId: undefined,
};

// Task 3.1: stream state, fed by FleetStartupService's snapshot+buffer+live
// pipeline (design.md: "SignalStore para el stream, httpResource() para
// HTTP" -- a pushed MQTT stream isn't a request that can be reloaded, so it
// doesn't belong in an httpResource).
export const FleetStore = signalStore(
  { providedIn: 'root' },
  withState<FleetStoreState>(INITIAL_STATE),
  withComputed(({ vehicles, filters }) => ({
    // Task 3.2
    visibleVehicles: computed(() => filterVehicles([...vehicles().values()], filters())),
  })),
  withMethods((store) => ({
    // A snapshot (GET /api/fleet/state) is always authoritative and fully
    // replaces whatever was known before -- it never merges into it. Called
    // both on first load and on every reconnect resync (design.md's
    // "repeat the full cycle" rule).
    applySnapshot(response: FleetStateResponse): void {
      const vehicles = new Map<string, VehicleState>();
      for (const vehicle of response.vehicles ?? []) {
        if (!vehicle.vehicleId) {
          continue;
        }
        vehicles.set(vehicle.vehicleId, toVehicleState(vehicle.vehicleId, vehicle));
      }
      patchState(store, { vehicles });
    },

    // Task 2.3: the monotonicity guard lives here so it applies uniformly
    // whether `update` comes from FleetStartupService's startup buffer
    // replay or the live stream afterwards -- both paths call this same
    // method, so there is exactly one place that decides "is this stale".
    applyUpdate(update: VehicleUpdate): void {
      const current = store.vehicles();
      const existing = current.get(update.vehicleId);

      // Task 2.4/2.5: eta updates carry no recordedAt of their own to
      // compare against (EtaPayload's own wire shape, deliberately minimal
      // -- see its class comment) -- the live MQTT stream already delivers
      // messages in publish order, and a stale eta simply gets overwritten
      // by the next one moments later, the same way alerts/presence updates
      // (which also carry no monotonicity guard here) already behave.
      if (update.kind === 'telemetry' && isStale(update.recordedAt, existing?.recordedAt)) {
        return;
      }

      const next = new Map(current);
      next.set(update.vehicleId, mergeUpdate(existing, update));
      patchState(store, { vehicles: next });
    },

    setFilters(filters: Partial<FleetFilters>): void {
      patchState(store, (state) => ({ filters: { ...state.filters, ...filters } }));
    },

    // Task 3.3's design gap: the historical track's httpResource needs a
    // reactive "which vehicle" signal (design.md's own `selectedVehicleId()`
    // example), and the map's marker click-to-select (task 4.2) is the first
    // producer of that signal before WU5's side panel exists. Kept on
    // FleetStore rather than a new store so there is exactly one selection
    // signal for the map, the track resource, and (WU5) the side panel to
    // share -- never three copies that could disagree.
    selectVehicle(vehicleId: string | undefined): void {
      patchState(store, { selectedVehicleId: vehicleId });
    },

    reset(): void {
      patchState(store, INITIAL_STATE);
    },
  })),
);

function toVehicleState(vehicleId: string, vehicle: VehicleStateResponse): VehicleState {
  return {
    vehicleId,
    label: vehicle.label,
    lat: vehicle.lat,
    lon: vehicle.lon,
    recordedAt: vehicle.recordedAt,
    motionState: vehicle.motionState,
    online: vehicle.online,
    destinationLat: vehicle.destinationLat,
    destinationLon: vehicle.destinationLon,
    etaSeconds: vehicle.etaSeconds,
    etaMarginSeconds: vehicle.etaMarginSeconds,
    etaCalculatedAt: vehicle.etaCalculatedAt,
  };
}

// Compares parsed instants rather than the raw ISO-8601 strings: two valid
// ISO-8601 UTC instants don't always sort correctly as plain strings (e.g.
// differing fractional-second precision at the same whole second), so this
// avoids a subtle false-positive/false-negative in the monotonicity guard.
function isStale(candidateRecordedAt: string, knownRecordedAt: string | undefined): boolean {
  if (!knownRecordedAt) {
    return false;
  }
  return Date.parse(candidateRecordedAt) <= Date.parse(knownRecordedAt);
}

function mergeUpdate(existing: VehicleState | undefined, update: VehicleUpdate): VehicleState {
  if (update.kind === 'telemetry') {
    return {
      ...existing,
      vehicleId: update.vehicleId,
      lat: update.lat,
      lon: update.lon,
      recordedAt: update.recordedAt,
      speedKmh: update.speedKmh,
      heading: update.heading,
    };
  }
  if (update.kind === 'eta') {
    return {
      ...existing,
      vehicleId: update.vehicleId,
      etaSeconds: update.etaSeconds,
      etaMarginSeconds: update.etaMarginSeconds,
      etaCalculatedAt: update.calculatedAt,
    };
  }
  return { ...existing, vehicleId: update.vehicleId, online: update.online };
}

function filterVehicles(vehicles: VehicleState[], filters: FleetFilters): VehicleState[] {
  return vehicles.filter((vehicle) => matchesFilters(vehicle, filters));
}

function matchesFilters(vehicle: VehicleState, filters: FleetFilters): boolean {
  if (filters.motionState && vehicle.motionState !== filters.motionState) {
    return false;
  }
  if (filters.onlineOnly && !vehicle.online) {
    return false;
  }
  const term = filters.search.trim().toLowerCase();
  if (term && !matchesSearch(vehicle, term)) {
    return false;
  }
  return true;
}

function matchesSearch(vehicle: VehicleState, term: string): boolean {
  return (vehicle.label?.toLowerCase().includes(term) ?? false) || vehicle.vehicleId.toLowerCase().includes(term);
}

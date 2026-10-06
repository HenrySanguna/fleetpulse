import type { VehicleState } from '../models/vehicle-state.model';

// Mirrors the backend's FleetStateService threshold: a vehicle that has not
// reported for this long is shown offline even if its stored flag is still true
// (no Last Will fires when a device just stops publishing).
export const ONLINE_STALENESS_MS = 5 * 60 * 1000;

export function isEffectivelyOnline(vehicle: Pick<VehicleState, 'online' | 'recordedAt'>, nowMs: number): boolean {
  if (!vehicle.online || !vehicle.recordedAt) {
    return false;
  }
  const recordedAtMs = Date.parse(vehicle.recordedAt);
  return Number.isFinite(recordedAtMs) && recordedAtMs > nowMs - ONLINE_STALENESS_MS;
}

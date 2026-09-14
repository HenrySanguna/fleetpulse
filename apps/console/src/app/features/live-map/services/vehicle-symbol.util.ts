import type { Feature, FeatureCollection, Point } from 'geojson';
import type { VehicleState } from '../models/vehicle-state.model';
import type { InterpolatedVehiclePosition } from './vehicle-interpolation';

export interface VehicleFeatureProperties {
  readonly vehicleId: string;
  readonly label: string;
  readonly heading: number;
  readonly motionState: string;
  readonly online: boolean;
  // task 4.5's dimming signal: no reported position within the expected
  // window, distinct from `online` (task 4.3's own offline-dimming trigger).
  readonly stale: boolean;
  // Task 5.2: true for the vehicle FleetStore.selectedVehicleId() currently
  // points at, so the vehicle layer's paint expressions (LiveMapComponent)
  // can highlight it without a second lookup.
  readonly selected: boolean;
}

export const EMPTY_VEHICLE_FEATURE_COLLECTION: FeatureCollection<Point, VehicleFeatureProperties> = {
  type: 'FeatureCollection',
  features: [],
};

// Tasks 4.2/4.3: pure GeoJSON derivation, so LiveMapComponent only ever has
// to call `source.setData(...)` with this result -- MapLibre's symbol layer
// (not DOM markers, task 4.2) reads `heading`/`motionState`/`online`/`stale`
// through data-driven paint/layout expressions declared once in the
// component, so styling never has to be recomputed per feature here.
//
// Geometry always comes from the interpolation engine's *visual* sample,
// never straight from `vehicle.lat`/`vehicle.lon` -- that is task 4.4's
// entire point. Every other property always comes from the real reported
// `VehicleState`, matching design.md's rule that interpolation is purely a
// position effect and never substituted into the vehicle's real data.
export function toVehicleFeatureCollection(
  vehicles: readonly VehicleState[],
  positions: readonly InterpolatedVehiclePosition[],
  selectedVehicleId?: string,
): FeatureCollection<Point, VehicleFeatureProperties> {
  const positionByVehicleId = new Map(positions.map((position) => [position.vehicleId, position]));
  const features: Feature<Point, VehicleFeatureProperties>[] = [];

  for (const vehicle of vehicles) {
    const position = positionByVehicleId.get(vehicle.vehicleId);
    if (!position) {
      // No reported lat/lon yet for this vehicle -- nothing to place on the
      // map (never fabricate a coordinate).
      continue;
    }
    features.push({
      type: 'Feature',
      geometry: { type: 'Point', coordinates: [position.lon, position.lat] },
      properties: {
        vehicleId: vehicle.vehicleId,
        label: vehicle.label ?? vehicle.vehicleId,
        heading: vehicle.heading ?? 0,
        motionState: vehicle.motionState ?? 'STOPPED',
        online: vehicle.online ?? false,
        stale: position.stale,
        selected: vehicle.vehicleId === selectedVehicleId,
      },
    });
  }

  return { type: 'FeatureCollection', features };
}

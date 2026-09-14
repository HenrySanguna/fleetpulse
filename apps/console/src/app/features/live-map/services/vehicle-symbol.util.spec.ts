import type { VehicleState } from '../models/vehicle-state.model';
import type { InterpolatedVehiclePosition } from './vehicle-interpolation';
import { toVehicleFeatureCollection } from './vehicle-symbol.util';

function vehicle(overrides: Partial<VehicleState> & { vehicleId: string }): VehicleState {
  return overrides;
}

function position(overrides: Partial<InterpolatedVehiclePosition> & { vehicleId: string }): InterpolatedVehiclePosition {
  return { lat: 0, lon: 0, stale: false, ...overrides };
}

describe('toVehicleFeatureCollection', () => {
  it('omits vehicles with no interpolated position yet', () => {
    const collection = toVehicleFeatureCollection([vehicle({ vehicleId: 'v1' })], []);

    expect(collection.features).toEqual([]);
  });

  it('uses the interpolated position for geometry, in [lon, lat] GeoJSON order', () => {
    const collection = toVehicleFeatureCollection(
      [vehicle({ vehicleId: 'v1', lat: 999, lon: 999 })],
      [position({ vehicleId: 'v1', lat: 10, lon: 20 })],
    );

    expect(collection.features[0]?.geometry).toEqual({ type: 'Point', coordinates: [20, 10] });
  });

  // Task 4.3
  it('carries heading, motionState, online and stale through as feature properties', () => {
    const collection = toVehicleFeatureCollection(
      [vehicle({ vehicleId: 'v1', heading: 90, motionState: 'MOVING', online: true, label: 'Truck 1' })],
      [position({ vehicleId: 'v1', stale: false })],
    );

    expect(collection.features[0]?.properties).toEqual({
      vehicleId: 'v1',
      label: 'Truck 1',
      heading: 90,
      motionState: 'MOVING',
      online: true,
      stale: false,
      selected: false,
    });
  });

  it('defaults heading to 0, motionState to STOPPED, online to false, and label to the vehicle id', () => {
    const collection = toVehicleFeatureCollection(
      [vehicle({ vehicleId: 'v1' })],
      [position({ vehicleId: 'v1' })],
    );

    expect(collection.features[0]?.properties).toEqual({
      vehicleId: 'v1',
      label: 'v1',
      heading: 0,
      motionState: 'STOPPED',
      online: false,
      stale: false,
      selected: false,
    });
  });

  // Task 4.5
  it('passes the stale flag through unchanged, for offline-independent dimming', () => {
    const collection = toVehicleFeatureCollection(
      [vehicle({ vehicleId: 'v1', online: true })],
      [position({ vehicleId: 'v1', stale: true })],
    );

    expect(collection.features[0]?.properties.stale).toBe(true);
  });

  // Task 5.2: the map's own highlight signal for the selected vehicle.
  it('marks the feature matching selectedVehicleId as selected, and every other feature as not', () => {
    const collection = toVehicleFeatureCollection(
      [vehicle({ vehicleId: 'v1' }), vehicle({ vehicleId: 'v2' })],
      [position({ vehicleId: 'v1' }), position({ vehicleId: 'v2' })],
      'v2',
    );

    const byId = new Map(collection.features.map((feature) => [feature.properties.vehicleId, feature.properties]));
    expect(byId.get('v1')?.selected).toBe(false);
    expect(byId.get('v2')?.selected).toBe(true);
  });

  it('marks no feature as selected when selectedVehicleId is undefined', () => {
    const collection = toVehicleFeatureCollection([vehicle({ vehicleId: 'v1' })], [position({ vehicleId: 'v1' })]);

    expect(collection.features[0]?.properties.selected).toBe(false);
  });
});

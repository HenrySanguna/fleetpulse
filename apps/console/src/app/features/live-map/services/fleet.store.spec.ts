import { TestBed } from '@angular/core/testing';
import type { FleetStateResponse } from '@fleetpulse/api-client';
import { FleetStore } from './fleet.store';

describe('FleetStore', () => {
  let store: InstanceType<typeof FleetStore>;

  beforeEach(() => {
    TestBed.configureTestingModule({});
    store = TestBed.inject(FleetStore);
  });

  it('starts with an empty fleet', () => {
    expect(store.vehicles().size).toBe(0);
    expect(store.visibleVehicles()).toEqual([]);
  });

  // Task 3.1
  it('applySnapshot replaces the full vehicle map', () => {
    const snapshot: FleetStateResponse = {
      vehicles: [
        { vehicleId: 'v1', lat: 1, lon: 2, recordedAt: '2026-01-01T00:00:00Z', online: true },
        { vehicleId: 'v2', online: false },
      ],
    };

    store.applySnapshot(snapshot);

    expect(store.vehicles().size).toBe(2);
    expect(store.vehicles().get('v1')).toEqual(
      expect.objectContaining({ vehicleId: 'v1', lat: 1, lon: 2, online: true }),
    );

    store.applySnapshot({ vehicles: [{ vehicleId: 'v3', online: true }] });
    expect(store.vehicles().size).toBe(1);
    expect(store.vehicles().has('v1')).toBe(false);
  });

  it('applyUpdate merges a telemetry update onto the existing vehicle without touching online/motionState', () => {
    store.applySnapshot({
      vehicles: [
        {
          vehicleId: 'v1',
          lat: 1,
          lon: 1,
          recordedAt: '2026-01-01T00:00:00Z',
          online: true,
          motionState: 'MOVING',
        },
      ],
    });

    store.applyUpdate({
      kind: 'telemetry',
      vehicleId: 'v1',
      recordedAt: '2026-01-01T00:00:10Z',
      lat: 5,
      lon: 6,
      speedKmh: 30,
    });

    expect(store.vehicles().get('v1')).toEqual({
      vehicleId: 'v1',
      lat: 5,
      lon: 6,
      recordedAt: '2026-01-01T00:00:10Z',
      speedKmh: 30,
      heading: undefined,
      online: true,
      motionState: 'MOVING',
    });
  });

  // Task 2.3 / Test 6.1
  it('applyUpdate discards a telemetry update older than the currently known position', () => {
    store.applySnapshot({
      vehicles: [{ vehicleId: 'v1', lat: 1, lon: 1, recordedAt: '2026-01-01T00:00:10Z' }],
    });

    store.applyUpdate({
      kind: 'telemetry',
      vehicleId: 'v1',
      recordedAt: '2026-01-01T00:00:00Z',
      lat: 99,
      lon: 99,
    });

    expect(store.vehicles().get('v1')).toEqual(
      expect.objectContaining({ lat: 1, lon: 1, recordedAt: '2026-01-01T00:00:10Z' }),
    );
  });

  // Task 2.1/2.4: the snapshot seeds destination/eta fields the same way it
  // already seeds lat/lon/motionState.
  it('applySnapshot carries destination and eta fields when present', () => {
    store.applySnapshot({
      vehicles: [
        {
          vehicleId: 'v1',
          destinationLat: 4.8,
          destinationLon: -74.1,
          etaSeconds: 900,
          etaMarginSeconds: 270,
          etaCalculatedAt: '2026-01-01T00:00:00Z',
        },
      ],
    });

    expect(store.vehicles().get('v1')).toEqual(
      expect.objectContaining({
        destinationLat: 4.8,
        destinationLon: -74.1,
        etaSeconds: 900,
        etaMarginSeconds: 270,
        etaCalculatedAt: '2026-01-01T00:00:00Z',
      }),
    );
  });

  // Task 2.4/2.5: a live eta update touches only the eta fields, leaving
  // position/motion/online untouched -- the same "merge, don't replace"
  // shape the presence merge test below already proves for its own fields.
  it('applyUpdate merges an eta update, touching only the eta fields', () => {
    store.applySnapshot({
      vehicles: [{ vehicleId: 'v1', lat: 1, lon: 1, recordedAt: '2026-01-01T00:00:00Z', online: true }],
    });

    store.applyUpdate({ kind: 'eta', vehicleId: 'v1', etaSeconds: 900, etaMarginSeconds: 270, calculatedAt: '2026-01-01T00:00:05Z' });

    expect(store.vehicles().get('v1')).toEqual(
      expect.objectContaining({
        lat: 1,
        lon: 1,
        recordedAt: '2026-01-01T00:00:00Z',
        online: true,
        etaSeconds: 900,
        etaMarginSeconds: 270,
        etaCalculatedAt: '2026-01-01T00:00:05Z',
      }),
    );
  });

  it('applyUpdate merges a presence update, touching only the online flag', () => {
    store.applySnapshot({
      vehicles: [{ vehicleId: 'v1', lat: 1, lon: 1, recordedAt: '2026-01-01T00:00:00Z', online: true }],
    });

    store.applyUpdate({ kind: 'presence', vehicleId: 'v1', online: false });

    expect(store.vehicles().get('v1')).toEqual(
      expect.objectContaining({ lat: 1, lon: 1, recordedAt: '2026-01-01T00:00:00Z', online: false }),
    );
  });

  // Task 3.2
  describe('visibleVehicles', () => {
    beforeEach(() => {
      store.applySnapshot({
        vehicles: [
          { vehicleId: 'v1', label: 'Truck 1', online: true, motionState: 'MOVING' },
          { vehicleId: 'v2', label: 'Van 2', online: false, motionState: 'STOPPED' },
          { vehicleId: 'v3', label: 'Truck 3', online: true, motionState: 'STOPPED' },
        ],
      });
    });

    it('filters by motion state', () => {
      store.setFilters({ motionState: 'STOPPED' });
      expect(store.visibleVehicles().map((v) => v.vehicleId).sort()).toEqual(['v2', 'v3']);
    });

    it('filters online-only vehicles', () => {
      store.setFilters({ onlineOnly: true });
      expect(store.visibleVehicles().map((v) => v.vehicleId).sort()).toEqual(['v1', 'v3']);
    });

    it('filters by case-insensitive label search', () => {
      store.setFilters({ search: 'truck' });
      expect(store.visibleVehicles().map((v) => v.vehicleId).sort()).toEqual(['v1', 'v3']);
    });

    it('combines all active filters', () => {
      store.setFilters({ onlineOnly: true, motionState: 'STOPPED' });
      expect(store.visibleVehicles().map((v) => v.vehicleId)).toEqual(['v3']);
    });
  });

  // Task 3.3 (shared selection signal for the track httpResource)
  it('selectVehicle sets and clears the selected vehicle id', () => {
    expect(store.selectedVehicleId()).toBeUndefined();

    store.selectVehicle('v1');
    expect(store.selectedVehicleId()).toBe('v1');

    store.selectVehicle(undefined);
    expect(store.selectedVehicleId()).toBeUndefined();
  });
});

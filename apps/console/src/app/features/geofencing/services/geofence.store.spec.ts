import { TestBed } from '@angular/core/testing';
import type { GeofenceResponse } from '@fleetpulse/api-client';
import { GeofenceStore } from './geofence.store';

describe('GeofenceStore', () => {
  let store: InstanceType<typeof GeofenceStore>;

  beforeEach(() => {
    TestBed.configureTestingModule({});
    store = TestBed.inject(GeofenceStore);
  });

  it('starts empty, not loading, with no error and nothing selected', () => {
    expect(store.geofences()).toEqual([]);
    expect(store.loading()).toBe(false);
    expect(store.error()).toBeUndefined();
    expect(store.selected()).toBeUndefined();
  });

  it('setLoading(true) clears any previous error', () => {
    store.setError('boom');
    store.setLoading(true);

    expect(store.loading()).toBe(true);
    expect(store.error()).toBeUndefined();
  });

  it('setGeofences replaces the list and clears loading/error', () => {
    store.setLoading(true);
    const geofences: GeofenceResponse[] = [{ id: 'g1', name: 'Depot' }];

    store.setGeofences(geofences);

    expect(store.geofences()).toEqual(geofences);
    expect(store.loading()).toBe(false);
    expect(store.error()).toBeUndefined();
  });

  it('setError clears loading and records the message', () => {
    store.setLoading(true);

    store.setError('failed to load');

    expect(store.loading()).toBe(false);
    expect(store.error()).toBe('failed to load');
  });

  describe('upsertGeofence', () => {
    it('appends a new geofence not already in the list', () => {
      store.setGeofences([{ id: 'g1', name: 'Depot' }]);

      store.upsertGeofence({ id: 'g2', name: 'Yard' });

      expect(store.geofences().map((g) => g.id)).toEqual(['g1', 'g2']);
    });

    it('replaces an existing geofence with the same id', () => {
      store.setGeofences([{ id: 'g1', name: 'Depot' }]);

      store.upsertGeofence({ id: 'g1', name: 'Depot (renamed)' });

      expect(store.geofences()).toEqual([{ id: 'g1', name: 'Depot (renamed)' }]);
    });
  });

  it('select sets and clears the selected geofence id, and `selected` resolves it from the list', () => {
    store.setGeofences([{ id: 'g1', name: 'Depot' }]);

    store.select('g1');
    expect(store.selected()).toEqual({ id: 'g1', name: 'Depot' });

    store.select(undefined);
    expect(store.selected()).toBeUndefined();
  });
});

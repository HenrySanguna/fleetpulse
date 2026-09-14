import { TestBed } from '@angular/core/testing';
import { FleetStore } from './fleet.store';
import { TELEMETRY_INTERVAL_MS, VehicleInterpolationEngine } from './vehicle-interpolation';

describe('VehicleInterpolationEngine', () => {
  it('renders the first reported position immediately, not stale', () => {
    const engine = new VehicleInterpolationEngine();

    engine.recordPosition('v1', { lat: 10, lon: 20 }, 0);

    expect(engine.sampleAll(0)).toEqual([{ vehicleId: 'v1', lat: 10, lon: 20, stale: false }]);
  });

  // Task 4.4
  it('interpolates linearly towards the newest reported position over the expected window', () => {
    const engine = new VehicleInterpolationEngine(1_000);
    engine.recordPosition('v1', { lat: 0, lon: 0 }, 0);
    engine.recordPosition('v1', { lat: 10, lon: 20 }, 0);

    expect(engine.sampleAll(500)).toEqual([{ vehicleId: 'v1', lat: 5, lon: 10, stale: false }]);
    expect(engine.sampleAll(1_000)).toEqual([{ vehicleId: 'v1', lat: 10, lon: 20, stale: false }]);
  });

  it('a new update mid-interpolation starts from the current visual position, not the old target', () => {
    const engine = new VehicleInterpolationEngine(1_000);
    engine.recordPosition('v1', { lat: 0, lon: 0 }, 0);
    engine.recordPosition('v1', { lat: 10, lon: 0 }, 0);

    // Halfway through the first animation (visual position is lat 5).
    engine.recordPosition('v1', { lat: 5, lon: 100 }, 500);

    expect(engine.sampleAll(500)).toEqual([{ vehicleId: 'v1', lat: 5, lon: 0, stale: false }]);
    // Halfway into the *new* window (started at t=500, one window is 1000ms).
    expect(engine.sampleAll(1_000)).toEqual([{ vehicleId: 'v1', lat: 5, lon: 50, stale: false }]);
    expect(engine.sampleAll(1_500)).toEqual([{ vehicleId: 'v1', lat: 5, lon: 100, stale: false }]);
  });

  // Task 4.5 / Test 6.4: interpolation stops (freezes), it never extrapolates.
  it('freezes at the last reported position and marks stale once the window is exceeded', () => {
    const engine = new VehicleInterpolationEngine(1_000);
    engine.recordPosition('v1', { lat: 0, lon: 0 }, 0);
    engine.recordPosition('v1', { lat: 10, lon: 20 }, 0);

    expect(engine.sampleAll(1_000)).toEqual([{ vehicleId: 'v1', lat: 10, lon: 20, stale: false }]);
    // Well past one window with no new sample: still exactly `to`, never beyond it.
    expect(engine.sampleAll(5_000)).toEqual([{ vehicleId: 'v1', lat: 10, lon: 20, stale: true }]);
    expect(engine.sampleAll(50_000)).toEqual([{ vehicleId: 'v1', lat: 10, lon: 20, stale: true }]);
  });

  it('a resumed update after a stale gap animates from the frozen position, not a stale interpolated one', () => {
    const engine = new VehicleInterpolationEngine(1_000);
    engine.recordPosition('v1', { lat: 0, lon: 0 }, 0);
    engine.recordPosition('v1', { lat: 10, lon: 20 }, 0);

    engine.recordPosition('v1', { lat: 30, lon: 40 }, 10_000);

    expect(engine.sampleAll(10_000)).toEqual([{ vehicleId: 'v1', lat: 10, lon: 20, stale: false }]);
    expect(engine.sampleAll(10_500)).toEqual([{ vehicleId: 'v1', lat: 20, lon: 30, stale: false }]);
  });

  it('forget() removes a vehicle from future samples', () => {
    const engine = new VehicleInterpolationEngine();
    engine.recordPosition('v1', { lat: 1, lon: 1 }, 0);

    engine.forget('v1');

    expect(engine.sampleAll(0)).toEqual([]);
  });

  it('defaults its window to the telemetry cadence (5s)', () => {
    const engine = new VehicleInterpolationEngine();
    engine.recordPosition('v1', { lat: 0, lon: 0 }, 0);
    engine.recordPosition('v1', { lat: 10, lon: 10 }, 0);

    expect(engine.sampleAll(TELEMETRY_INTERVAL_MS)[0]?.stale).toBe(false);
    expect(engine.sampleAll(TELEMETRY_INTERVAL_MS + 1)[0]?.stale).toBe(true);
  });

  // Test 6.3 underlying logic (design.md: interpolation is purely visual --
  // the component-level assertion that the detail panel shows the reported,
  // not interpolated, position moves to WU5 once that panel exists; this
  // proves the data-level separation the panel will rely on).
  it('never substitutes the interpolated position into FleetStore -- the real reported state stays untouched', () => {
    TestBed.configureTestingModule({});
    const store = TestBed.inject(FleetStore);
    const engine = new VehicleInterpolationEngine(1_000);

    store.applySnapshot({
      vehicles: [{ vehicleId: 'v1', lat: 0, lon: 0, recordedAt: '2026-01-01T00:00:00Z' }],
    });
    engine.recordPosition('v1', { lat: 0, lon: 0 }, 0);

    store.applyUpdate({ kind: 'telemetry', vehicleId: 'v1', recordedAt: '2026-01-01T00:00:05Z', lat: 10, lon: 20 });
    engine.recordPosition('v1', { lat: 10, lon: 20 }, 0);

    const visual = engine.sampleAll(500)[0];
    expect(visual).toEqual({ vehicleId: 'v1', lat: 5, lon: 10, stale: false });

    // The store's own state -- what any "vehicle detail" view reads -- is
    // always the exact reported value, regardless of interpolation progress.
    expect(store.vehicles().get('v1')).toEqual(
      expect.objectContaining({ lat: 10, lon: 20, recordedAt: '2026-01-01T00:00:05Z' }),
    );
  });
});

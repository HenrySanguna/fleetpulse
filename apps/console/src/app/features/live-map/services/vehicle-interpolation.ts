// Tasks 4.4/4.5: framework-agnostic interpolation engine -- deliberately not
// an Angular service (no DI, no `requestAnimationFrame` call inside it) so
// its timing math is unit-testable with synthetic timestamps instead of a
// real wall clock or a real rAF loop. LiveMapComponent owns the actual rAF
// loop and feeds real time into `sampleAll(performance.now())` every frame.
//
// design.md: telemetry arrives roughly every 5 seconds; moving the marker in
// one jump produces visible pops, so the position is interpolated towards
// each newly reported sample over that same window. If more than one window
// passes without a new sample, interpolation stops -- the marker freezes at
// the last reported position rather than extrapolating a guess.
export const TELEMETRY_INTERVAL_MS = 5_000;

export interface VehiclePositionSample {
  readonly lat: number;
  readonly lon: number;
}

export interface InterpolatedVehiclePosition extends VehiclePositionSample {
  readonly vehicleId: string;
  // True once more than one expected window has passed since the last
  // reported sample for this vehicle -- the marker should render dimmed
  // (task 4.5 / spec's "Ausencia prolongada de actualizaciones" scenario).
  readonly stale: boolean;
}

interface TrackedVehicle {
  readonly from: VehiclePositionSample;
  readonly to: VehiclePositionSample;
  readonly receivedAtMs: number;
}

export class VehicleInterpolationEngine {
  private readonly vehicles = new Map<string, TrackedVehicle>();

  constructor(private readonly intervalMs: number = TELEMETRY_INTERVAL_MS) {}

  // Records a newly *reported* position -- never call this with an
  // already-interpolated value. The new animation starts from wherever the
  // marker visually is right now (so a fresh update never snaps back), which
  // is either the previous target (if the last window fully elapsed) or the
  // in-progress interpolated point.
  recordPosition(vehicleId: string, position: VehiclePositionSample, nowMs: number = Date.now()): void {
    const tracked = this.vehicles.get(vehicleId);
    const from = tracked ? this.sampleOne(tracked, nowMs) : position;
    this.vehicles.set(vehicleId, { from, to: position, receivedAtMs: nowMs });
  }

  // Stops tracking a vehicle entirely (e.g. it left the visible/filtered
  // set). Distinct from "stale": a forgotten vehicle produces no sample at
  // all, a stale one still renders, dimmed, at its last known position.
  forget(vehicleId: string): void {
    this.vehicles.delete(vehicleId);
  }

  sampleAll(nowMs: number): InterpolatedVehiclePosition[] {
    return [...this.vehicles.entries()].map(([vehicleId, tracked]) => ({
      vehicleId,
      ...this.sampleOne(tracked, nowMs),
      stale: nowMs - tracked.receivedAtMs > this.intervalMs,
    }));
  }

  private sampleOne(tracked: TrackedVehicle, nowMs: number): VehiclePositionSample {
    const elapsed = nowMs - tracked.receivedAtMs;
    // Clamped to [0, 1]: past one full window the progress simply stays at
    // 1, i.e. the sample equals `to` forever -- this clamp is the entire
    // "stop interpolating, never extrapolate" rule from task 4.5.
    const progress = Math.min(Math.max(elapsed / this.intervalMs, 0), 1);
    return {
      lat: lerp(tracked.from.lat, tracked.to.lat, progress),
      lon: lerp(tracked.from.lon, tracked.to.lon, progress),
    };
  }
}

function lerp(a: number, b: number, t: number): number {
  return a + (b - a) * t;
}

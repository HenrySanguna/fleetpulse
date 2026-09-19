export type MotionState = 'MOVING' | 'IDLING' | 'STOPPED';

// Client-side state for a single vehicle in FleetStore. Mirrors
// GET /api/fleet/state's VehicleStateResponse shape (all fields but
// vehicleId optional -- a vehicle with no vehicle_state row yet still
// appears, offline/null-field, per FleetStateService).
//
// `motionState` is populated only from the HTTP snapshot: geo-core's
// MotionDetector needs a speed/duration streak history to classify
// MOVING/IDLING/STOPPED that only `processor` maintains server-side, so it
// is never recomputed client-side from a single live telemetry sample -- it
// stays at its last-known snapshot value until the next full resync
// (FleetStartupService resyncs on every initial connect and reconnect).
// Task 2.5 (06-add-trips-eta-alerts, WU2): destinationLat/destinationLon/
// etaSeconds/etaMarginSeconds/etaCalculatedAt mirror lat/lon/motionState's
// own "HTTP snapshot, then live MQTT stream" sourcing (VehicleStateResponse's
// own comment) -- etaSeconds/etaMarginSeconds are ALWAYS read together by
// every consumer (spec.md's "nunca como hora exacta sin margen"): there is
// no field shape that carries one without the other.
export interface VehicleState {
  readonly vehicleId: string;
  readonly label?: string;
  readonly lat?: number;
  readonly lon?: number;
  readonly recordedAt?: string;
  readonly speedKmh?: number;
  readonly heading?: number;
  readonly motionState?: MotionState;
  readonly online?: boolean;
  readonly destinationLat?: number;
  readonly destinationLon?: number;
  readonly etaSeconds?: number;
  readonly etaMarginSeconds?: number;
  readonly etaCalculatedAt?: string;
}

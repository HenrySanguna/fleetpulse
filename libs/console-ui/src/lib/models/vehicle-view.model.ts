// Presentational view models for `libs/console-ui`. Deliberately independent
// of `apps/console`'s own `VehicleState`/`FleetFilters`/`MqttConnectionStatus`
// types (features depend on this UI library, never the other way around) --
// TypeScript's structural typing means a feature can still pass its own
// state objects directly wherever these shapes are expected, with no mapping
// layer required, as long as the fields line up.
export type VehicleMotionState = 'MOVING' | 'IDLING' | 'STOPPED';

export interface VehicleView {
  readonly vehicleId: string;
  readonly label?: string;
  readonly lat?: number;
  readonly lon?: number;
  readonly recordedAt?: string;
  readonly speedKmh?: number;
  readonly heading?: number;
  readonly motionState?: VehicleMotionState;
  readonly online?: boolean;
}

export interface FleetFilterValue {
  readonly motionState?: VehicleMotionState;
  readonly onlineOnly: boolean;
  readonly search: string;
}

export type ConnectionStatusValue = 'disconnected' | 'connecting' | 'connected' | 'reconnecting';

import type { MotionState } from './vehicle-state.model';

// Task 3.2: filters backing FleetStore's `visibleVehicles` computed selector.
export interface FleetFilters {
  readonly motionState?: MotionState;
  readonly onlineOnly: boolean;
  readonly search: string;
}

export const INITIAL_FLEET_FILTERS: FleetFilters = {
  motionState: undefined,
  onlineOnly: false,
  search: '',
};

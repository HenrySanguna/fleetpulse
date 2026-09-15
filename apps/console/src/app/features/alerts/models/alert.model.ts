export type AlertType = 'geofence_enter' | 'geofence_exit' | 'speeding' | 'excessive_idle' | 'offline';

export interface Alert {
  readonly id: string;
  readonly vehicleId: string;
  readonly vehicleLabel: string;
  readonly type: AlertType;
  readonly detail: string;
  readonly occurredAt: string;
  readonly acknowledged: boolean;
}

// The filter bar (Alerts.dc.html mockup) has one "Geocerca" chip covering
// both geofence_enter and geofence_exit -- a UI category, not a literal
// Alert.type. Everything else filters 1:1 against its own AlertType.
export type AlertTypeFilter = 'all' | 'geofence' | 'speeding' | 'excessive_idle' | 'offline';

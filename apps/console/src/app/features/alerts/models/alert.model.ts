// Task 3.4: geofence_dwell added to match the real backend's alert_type
// vocabulary (alerts.alert_type CHECK constraint, V12) -- the mock-data era
// union predates knowing the exact backend contract and only had
// geofence_enter/geofence_exit. AlertResponse.alertType (backend/api/.../alerts)
// already carries this same lowercase wire value, so no case translation
// happens when mapping the real response onto this type.
export type AlertType = 'geofence_enter' | 'geofence_exit' | 'geofence_dwell' | 'speeding' | 'excessive_idle' | 'offline';

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
// every geofence_* type (enter/exit/dwell) -- a UI category, not a literal
// Alert.type. Everything else filters 1:1 against its own AlertType.
export type AlertTypeFilter = 'all' | 'geofence' | 'speeding' | 'excessive_idle' | 'offline';

package dev.fleetpulse.processor.alerts;

import java.util.Locale;

// Task 3.1's unified discriminator: the alerts table's alert_type CHECK
// constraint (V12) and this project's own console-facing AlertType union
// (apps/console/.../alerts/models/alert.model.ts) both name geofence values
// with a "geofence_" prefix -- unlike geofence_alerts' own now-retired
// unprefixed enter/exit/dwell (GeofenceAlertType, dev.fleetpulse.processor.geofencing,
// still used internally by that package for its own unchanged MQTT payload
// shape -- see GeofenceAlertType.storageValue() for the prefixed conversion
// used only when persisting into the unified table).
public enum AlertType {
    GEOFENCE_ENTER,
    GEOFENCE_EXIT,
    GEOFENCE_DWELL,
    SPEEDING,
    EXCESSIVE_IDLE,
    OFFLINE;

    // Wire/DB value, matches alerts.alert_type's CHECK constraint (V12).
    public String wireValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}

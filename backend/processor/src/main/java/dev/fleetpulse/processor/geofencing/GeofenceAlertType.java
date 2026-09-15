package dev.fleetpulse.processor.geofencing;

import java.util.Locale;

public enum GeofenceAlertType {
    ENTER,
    EXIT,
    DWELL;

    // Wire/DB value, matches geofence_alerts.alert_type's CHECK constraint
    // (V9): 'enter' | 'exit' | 'dwell'.
    public String wireValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}

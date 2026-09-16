package dev.fleetpulse.processor.geofencing;

import java.util.Locale;

public enum GeofenceAlertType {
    ENTER,
    EXIT,
    DWELL;

    // Wire value published in GeofenceAlertPayload's own MQTT payload shape,
    // unchanged since V9: 'enter' | 'exit' | 'dwell'. Retargeting persistence
    // at the unified `alerts` table (06-add-trips-eta-alerts/WU3) must never
    // change this external wire shape -- see storageValue() below for the
    // value actually persisted.
    public String wireValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    // Task 3.1 (06-add-trips-eta-alerts): the unified `alerts` table's
    // alert_type value for this geofence event -- "geofence_" + wireValue(),
    // matching AlertType's own naming (dev.fleetpulse.processor.alerts) and
    // the console's AlertType union. Deliberately NOT used for the MQTT
    // payload (MqttGeofenceAlertPublisher keeps publishing wireValue()
    // unprefixed): retargeting persistence must not change geofence alerts'
    // own external wire shape.
    public String storageValue() {
        return "geofence_" + wireValue();
    }
}

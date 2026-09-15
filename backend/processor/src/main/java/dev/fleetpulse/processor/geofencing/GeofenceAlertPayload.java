package dev.fleetpulse.processor.geofencing;

// Wire shape published to fleet/{orgId}/alerts. organizationId itself is not
// repeated in the payload since it is already encoded in the topic -- the
// same convention TelemetryPayload/PresencePayload follow for vehicleId.
record GeofenceAlertPayload(String alertId, String vehicleId, String geofenceId, String type, String occurredAt) {
}

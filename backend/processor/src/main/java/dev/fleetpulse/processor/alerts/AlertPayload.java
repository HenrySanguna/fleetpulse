package dev.fleetpulse.processor.alerts;

// Wire shape published to fleet/{orgId}/alerts for the alert types THIS work
// unit adds (speeding, excessive_idle) -- organizationId omitted since it is
// already encoded in the topic, matching GeofenceAlertPayload's own
// convention. context is null for both: neither alert type has a
// sub-resource today. A future alert type with a real context would
// populate it the same way JdbcGeofenceAlertWriter's own (unchanged) payload
// still carries geofenceId in its own separate shape.
record AlertPayload(String alertId, String vehicleId, String type, String context, String occurredAt) {
}

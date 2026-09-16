package dev.fleetpulse.processor.alerts;

import java.time.Instant;
import java.util.UUID;

// One fired alert (any type), ready to persist (JdbcAlertWriter) and publish
// (AlertPublisher). context is the alert's sub-resource, when it has one --
// a geofence id for the three geofence_* types (JdbcGeofenceAlertWriter's
// own conversion from GeofenceAlert), null for the vehicle-level types this
// work unit adds (speeding, excessive_idle) and for offline (not built until
// a later change). occurredAt is the telemetry message's own recordedAt, the
// same "describes when the condition was actually observed, not when this
// process happened to handle it" convention GeofenceAlert already
// established.
public record Alert(UUID id, UUID organizationId, UUID vehicleId, AlertType type, UUID context, Instant occurredAt) {
}

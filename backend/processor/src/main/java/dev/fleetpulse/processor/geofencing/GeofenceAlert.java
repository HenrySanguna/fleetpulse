package dev.fleetpulse.processor.geofencing;

import java.time.Instant;
import java.util.UUID;

// One fired alert, ready to be persisted (JdbcGeofenceAlertWriter, task 4.3)
// and published (GeofenceAlertPublisher, task 4.2). occurredAt is the
// telemetry message's own recordedAt, not Instant.now(): the alert
// describes when the transition/dwell threshold was actually observed, not
// when this process happened to handle it.
public record GeofenceAlert(
    UUID id,
    UUID organizationId,
    UUID vehicleId,
    UUID geofenceId,
    GeofenceAlertType type,
    Instant occurredAt
) {
}

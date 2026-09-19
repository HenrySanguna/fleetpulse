package dev.fleetpulse.api.alerts;

import java.time.Instant;
import java.util.UUID;

// Task 3.4: response DTO for GET /api/alerts and PATCH
// /api/alerts/{id}/acknowledge -- never the raw `alerts` row. vehicleLabel
// is joined from `vehicles` (never the vehicle_id alone), matching the
// console's already-built Alert model (apps/console/.../alerts/models/alert.model.ts)
// which renders `${vehicleLabel} · ${vehicleId}`. contextLabel is the
// geofence's own `name`, LEFT JOINed without an `is_active` filter -- an
// alert's history must keep showing a real geofence name even after that
// geofence is later soft-deleted (JdbcGeofenceRepository's own soft-delete
// convention), null for context-less alert types and for a null context.
// alertType is the lowercase wire value (see AlertType's own class comment
// for why), not the Java enum -- the console maps it 1:1 onto its own
// AlertType union with no case translation needed.
public record AlertResponse(
    UUID id,
    UUID vehicleId,
    String vehicleLabel,
    String alertType,
    UUID context,
    String contextLabel,
    Instant occurredAt,
    boolean acknowledged
) {
}

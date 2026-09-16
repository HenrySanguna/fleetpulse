package dev.fleetpulse.processor.eta;

// Task 2.4's wire shape, published to fleet/{orgId}/vehicle/{vehicleId}/eta.
// vehicleId/organizationId are deliberately NOT repeated in the body -- they
// already come from the topic segments, mirroring TelemetryPayload's own
// convention (telemetry's payload never repeats vehicleId either), not
// GeofenceAlertPayload's (whose topic is org-wide, not per-vehicle, so its
// payload has to carry vehicleId itself).
public record EtaPayload(long etaSeconds, long etaMarginSeconds, String calculatedAt) {
}

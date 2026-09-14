package dev.fleetpulse.processor.presence;

import java.util.UUID;

// Validated, strongly-typed shape produced by PresencePayloadParser once a
// raw PresencePayload has passed schema validation. vehicleId comes from the
// MQTT topic (fleet/{orgId}/vehicle/{vehicleId}/status), never from the
// payload body itself -- mirrors TelemetryMessage's own vehicleId source.
public record PresenceMessage(UUID vehicleId, boolean online) {
}

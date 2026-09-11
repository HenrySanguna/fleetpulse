package dev.fleetpulse.processor.telemetry;

import java.time.Instant;
import java.util.UUID;

// Validated, strongly-typed shape produced by TelemetryPayloadParser once a
// raw TelemetryPayload has passed schema and range validation. vehicleId
// comes from the MQTT topic (fleet/{orgId}/vehicle/{vehicleId}/telemetry),
// never from the payload body itself.
public record TelemetryMessage(
    UUID vehicleId,
    Instant recordedAt,
    double lat,
    double lon,
    Double speedKmh,
    Double heading,
    Boolean ignition
) {
}

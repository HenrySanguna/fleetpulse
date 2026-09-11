package dev.fleetpulse.processor.telemetry;

// Wire shape of the telemetry JSON payload published on
// fleet/{orgId}/vehicle/{vehicleId}/telemetry. design.md only specifies the
// persisted `positions` columns, not the wire format; field names mirror
// those columns directly (lat/lon instead of a nested location object to
// match geo-core's own GeoPoint(lat, lon, at) shape that a later work unit
// will convert this into) since no other source specifies one.
public record TelemetryPayload(
    String recordedAt,
    Double lat,
    Double lon,
    Double speedKmh,
    Double heading,
    Boolean ignition
) {
}

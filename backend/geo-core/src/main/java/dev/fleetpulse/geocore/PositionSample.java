package dev.fleetpulse.geocore;

import java.time.Instant;

// One recorded vehicle position, ordered by recordedAt -- the common input
// shape motion replay and trip segmentation both consume. Previously kept
// as two byte-for-byte identical copies (processor's trips.PositionSample,
// persisted-history shaped, and api's InProgressTripJdbcReader.
// MotionPositionSample, JDBC-row shaped); unified here (shared trip rules
// change) since neither module needs anything the other's copy did not
// already carry. heading is deliberately not a field: neither consumer
// needs it, same reasoning trips.PositionSample's own original comment
// already gave for leaving it off TelemetryMessage's live-ingestion shape.
public record PositionSample(Instant recordedAt, double lat, double lon, Double speedKmh, Boolean ignition) {
}

package dev.fleetpulse.processor.trips;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

// Tasks 1.2/1.3: read-only access TripSegmentationTask needs from
// positions/organizations/vehicles/trips. Kept separate from JdbcTripWriter
// (task 1.5) the same way GeofenceEvaluator (reads) stays separate from
// JdbcVehicleFenceStateWriter (writes) in 05-add-geofencing.
@Component
public class JdbcTripReader {

    // Every vehicle plus its organization's own configured stop threshold
    // (task 1.3) -- read once per scheduled run rather than once per
    // vehicle, the same "one upfront lookup, not N" shape
    // JdbcTelemetryPositionWriter.loadKnownMotionStates() already
    // established.
    private static final String VEHICLES_WITH_THRESHOLD_SQL = """
        SELECT v.id AS vehicle_id, v.organization_id, o.trip_stop_threshold_secs
        FROM vehicles v
        JOIN organizations o ON o.id = v.organization_id
        """;

    private static final String LAST_CLOSED_TRIP_ENDED_AT_SQL = """
        SELECT MAX(ended_at) FROM trips WHERE vehicle_id = ?
        """;

    // Task 1.2: only positions strictly after the last closed trip's
    // ended_at (or every position ever recorded, if none exists yet) and no
    // later than the processing horizon (design.md's deliberate delay) are
    // ever candidates for a NEW trip -- already-covered history is never
    // re-read.
    private static final String POSITIONS_SINCE_SQL = """
        SELECT recorded_at, ST_Y(location::geometry) AS lat, ST_X(location::geometry) AS lon, speed_kmh, ignition
        FROM positions
        WHERE vehicle_id = ? AND recorded_at > ? AND recorded_at <= ?
        ORDER BY recorded_at ASC
        """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcTripReader(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<VehicleThreshold> loadVehiclesWithThreshold() {
        return jdbcTemplate.query(VEHICLES_WITH_THRESHOLD_SQL, (rs, rowNum) -> new VehicleThreshold(
            (UUID) rs.getObject("vehicle_id"),
            (UUID) rs.getObject("organization_id"),
            Duration.ofSeconds(rs.getInt("trip_stop_threshold_secs"))
        ));
    }

    // Instant.EPOCH stands for "no closed trip exists yet for this
    // vehicle" -- positionsSince() below then naturally reads its ENTIRE
    // position history on the first ever run for that vehicle.
    public Instant lastClosedTripEndedAt(UUID vehicleId) {
        Timestamp endedAt = jdbcTemplate.queryForObject(LAST_CLOSED_TRIP_ENDED_AT_SQL, Timestamp.class, vehicleId);
        return endedAt == null ? Instant.EPOCH : endedAt.toInstant();
    }

    public List<PositionSample> positionsSince(UUID vehicleId, Instant since, Instant horizon) {
        return jdbcTemplate.query(
            POSITIONS_SINCE_SQL,
            ps -> {
                ps.setObject(1, vehicleId);
                ps.setTimestamp(2, Timestamp.from(since));
                ps.setTimestamp(3, Timestamp.from(horizon));
            },
            (rs, rowNum) -> toPositionSample(rs)
        );
    }

    private static PositionSample toPositionSample(ResultSet rs) throws SQLException {
        float speedKmh = rs.getFloat("speed_kmh");
        Double speed = rs.wasNull() ? null : (double) speedKmh;
        boolean ignition = rs.getBoolean("ignition");
        Boolean ignitionValue = rs.wasNull() ? null : ignition;
        return new PositionSample(
            rs.getTimestamp("recorded_at").toInstant(), rs.getDouble("lat"), rs.getDouble("lon"), speed, ignitionValue
        );
    }

    public record VehicleThreshold(UUID vehicleId, UUID organizationId, Duration stopThreshold) {
    }
}

package dev.fleetpulse.api.reports;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

// Task 10: the only reads InProgressTripCalculator needs that
// JdbcActivityReportRepository deliberately does not provide (that
// repository's own class comment documents why it never touches
// `positions`) -- kept as its own component rather than folded into
// JdbcActivityReportRepository so that class's "never scans positions"
// guarantee (proven by domain's ActivityReportQueryPlanTest) stays true
// and unchanged for its own two queries.
//
// lastClosedTripEndedAt/positionsSince mirror processor's own
// JdbcTripReader (lastClosedTripEndedAt/positionsSince) exactly -- not
// reused directly since api cannot depend on processor. stopThreshold
// reads organizations.trip_stop_threshold_secs (V10) the same way
// JdbcTripReader's own VEHICLES_WITH_THRESHOLD_SQL does, just scoped to one
// already-known organization instead of loading every vehicle up front.
@Component
class InProgressTripJdbcReader {

    private static final String LAST_CLOSED_TRIP_ENDED_AT_SQL = "SELECT MAX(ended_at) FROM trips WHERE vehicle_id = ?";

    private static final String STOP_THRESHOLD_SQL = "SELECT trip_stop_threshold_secs FROM organizations WHERE id = ?";

    private static final String POSITIONS_SINCE_SQL = """
        SELECT recorded_at, ST_Y(location::geometry) AS lat, ST_X(location::geometry) AS lon, speed_kmh, ignition
        FROM positions
        WHERE vehicle_id = ? AND recorded_at > ? AND recorded_at <= ?
        ORDER BY recorded_at ASC
        """;

    private final ObjectProvider<JdbcTemplate> jdbcTemplate;

    InProgressTripJdbcReader(ObjectProvider<JdbcTemplate> jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // Instant.EPOCH stands for "no closed trip exists yet for this
    // vehicle" -- same sentinel JdbcTripReader.lastClosedTripEndedAt() uses,
    // so positionsSince() below naturally reads this vehicle's entire
    // position history when nothing has ever closed.
    Instant lastClosedTripEndedAt(UUID vehicleId) {
        Timestamp endedAt = jdbcTemplate.getObject().queryForObject(LAST_CLOSED_TRIP_ENDED_AT_SQL, Timestamp.class, vehicleId);
        return endedAt == null ? Instant.EPOCH : endedAt.toInstant();
    }

    Duration stopThreshold(UUID organizationId) {
        Integer secs = jdbcTemplate.getObject().queryForObject(STOP_THRESHOLD_SQL, Integer.class, organizationId);
        return Duration.ofSeconds(secs);
    }

    List<MotionPositionSample> positionsSince(UUID vehicleId, Instant since, Instant until) {
        return jdbcTemplate.getObject().query(
            POSITIONS_SINCE_SQL,
            ps -> {
                ps.setObject(1, vehicleId);
                ps.setTimestamp(2, Timestamp.from(since));
                ps.setTimestamp(3, Timestamp.from(until));
            },
            (rs, rowNum) -> toSample(rs)
        );
    }

    private static MotionPositionSample toSample(ResultSet rs) throws SQLException {
        float speedKmh = rs.getFloat("speed_kmh");
        Double speed = rs.wasNull() ? null : (double) speedKmh;
        boolean ignition = rs.getBoolean("ignition");
        Boolean ignitionValue = rs.wasNull() ? null : ignition;
        return new MotionPositionSample(rs.getTimestamp("recorded_at").toInstant(), rs.getDouble("lat"), rs.getDouble("lon"), speed, ignitionValue);
    }

    // Mirrors processor's own trips.PositionSample (recordedAt/lat/lon/
    // speedKmh/ignition) -- not reused directly for the same module-boundary
    // reason as the rest of this class.
    record MotionPositionSample(Instant recordedAt, double lat, double lon, Double speedKmh, Boolean ignition) {
    }
}

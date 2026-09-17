package dev.fleetpulse.processor.rollups;

import dev.fleetpulse.processor.trips.PositionSample;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

// Task 4.1: read-only access VehicleRollupTask needs from vehicles/positions.
// Kept separate from JdbcHourlyRollupWriter/JdbcDailyRollupWriter the same
// way JdbcTripReader stays separate from JdbcTripWriter (WU1).
@Component
public class JdbcRollupReader {

    private static final String VEHICLES_SQL = "SELECT id AS vehicle_id, organization_id FROM vehicles";

    // Task 4.1's wide-window recompute needs one extra "anchor" position from
    // strictly before windowStart, ordered before the in-window rows, purely
    // so HourlyRollupAggregator can compute a correct leg into the very
    // first in-window position instead of treating it as a lone point with
    // no predecessor. The anchor's own hour bucket is dropped by the
    // aggregator (it is always strictly before windowStart, an hour-aligned
    // boundary), so it can never be mistaken for a row this run intends to
    // (re)write. An outer ORDER BY re-sorts the two UNION ALL legs into one
    // ascending timeline.
    private static final String POSITIONS_FOR_RECOMPUTE_SQL = """
        SELECT recorded_at, lat, lon, speed_kmh, ignition FROM (
            (SELECT recorded_at, ST_Y(location::geometry) AS lat, ST_X(location::geometry) AS lon, speed_kmh, ignition
             FROM positions
             WHERE vehicle_id = ? AND recorded_at < ?
             ORDER BY recorded_at DESC
             LIMIT 1)
            UNION ALL
            (SELECT recorded_at, ST_Y(location::geometry) AS lat, ST_X(location::geometry) AS lon, speed_kmh, ignition
             FROM positions
             WHERE vehicle_id = ? AND recorded_at >= ? AND recorded_at < ?)
        ) anchor_and_window
        ORDER BY recorded_at ASC
        """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcRollupReader(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<VehicleRef> loadVehicles() {
        return jdbcTemplate.query(
            VEHICLES_SQL,
            (rs, rowNum) -> new VehicleRef((UUID) rs.getObject("vehicle_id"), (UUID) rs.getObject("organization_id"))
        );
    }

    public List<PositionSample> positionsForRecompute(UUID vehicleId, Instant windowStart, Instant windowEnd) {
        return jdbcTemplate.query(
            POSITIONS_FOR_RECOMPUTE_SQL,
            ps -> {
                ps.setObject(1, vehicleId);
                ps.setTimestamp(2, Timestamp.from(windowStart));
                ps.setObject(3, vehicleId);
                ps.setTimestamp(4, Timestamp.from(windowStart));
                ps.setTimestamp(5, Timestamp.from(windowEnd));
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

    public record VehicleRef(UUID vehicleId, UUID organizationId) {
    }
}

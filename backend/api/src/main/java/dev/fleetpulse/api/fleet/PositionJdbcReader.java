package dev.fleetpulse.api.fleet;

import dev.fleetpulse.geocore.GeoPoint;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

// Task 2.1 extension: reads the append-only `positions` history with
// JdbcTemplate, not JPA -- same reasoning as VehicleStateJdbcReader (no JPA
// entity for this PostGIS-geography-backed table; JdbcTemplate resolved via
// ObjectProvider so this component still constructs in DataSource-less
// profiles). Returns geo-core's own GeoPoint directly: it is exactly the
// input type Geo.simplifyTrack (VehicleTrackService) needs, no intermediate
// DTO required for an internal read projection.
//
// The window is always bounded by the caller (VehicleTrackService), and at
// most maxRows of the most recent rows are read: the query orders newest
// first so LIMIT keeps the tail of the window, then the result is reversed
// back to ascending order for Geo.simplifyTrack.
@Component
class PositionJdbcReader {

    private static final String SELECT_TRACK_SQL = """
        SELECT ST_Y(location::geometry) AS lat, ST_X(location::geometry) AS lon, recorded_at
        FROM positions
        WHERE vehicle_id = ?
          AND recorded_at >= ?
          AND recorded_at <= ?
        ORDER BY recorded_at DESC
        LIMIT ?
        """;

    private final ObjectProvider<JdbcTemplate> jdbcTemplate;

    PositionJdbcReader(ObjectProvider<JdbcTemplate> jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    List<GeoPoint> findTrack(UUID vehicleId, Instant from, Instant to, int maxRows) {
        List<GeoPoint> newestFirst = jdbcTemplate.getObject().query(
            SELECT_TRACK_SQL,
            ps -> {
                ps.setObject(1, vehicleId);
                ps.setTimestamp(2, Timestamp.from(from));
                ps.setTimestamp(3, Timestamp.from(to));
                ps.setInt(4, maxRows);
            },
            (rs, rowNum) -> new GeoPoint(rs.getDouble("lat"), rs.getDouble("lon"), rs.getTimestamp("recorded_at").toInstant())
        );
        return newestFirst.reversed();
    }
}

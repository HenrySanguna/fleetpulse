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
// from/to bound the query with COALESCE against Postgres' -infinity/infinity
// timestamptz literals instead of the "(? IS NULL OR col >= ?)" pattern used
// elsewhere for optional filters, since each bound only needs a single bind
// parameter this way.
@Component
class PositionJdbcReader {

    private static final String SELECT_TRACK_SQL = """
        SELECT ST_Y(location::geometry) AS lat, ST_X(location::geometry) AS lon, recorded_at
        FROM positions
        WHERE vehicle_id = ?
          AND recorded_at >= COALESCE(?, '-infinity'::timestamptz)
          AND recorded_at <= COALESCE(?, 'infinity'::timestamptz)
        ORDER BY recorded_at ASC
        """;

    private final ObjectProvider<JdbcTemplate> jdbcTemplate;

    PositionJdbcReader(ObjectProvider<JdbcTemplate> jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    List<GeoPoint> findTrack(UUID vehicleId, Instant from, Instant to) {
        Timestamp fromTs = from == null ? null : Timestamp.from(from);
        Timestamp toTs = to == null ? null : Timestamp.from(to);
        return jdbcTemplate.getObject().query(
            SELECT_TRACK_SQL,
            ps -> {
                ps.setObject(1, vehicleId);
                ps.setTimestamp(2, fromTs);
                ps.setTimestamp(3, toTs);
            },
            (rs, rowNum) -> new GeoPoint(rs.getDouble("lat"), rs.getDouble("lon"), rs.getTimestamp("recorded_at").toInstant())
        );
    }
}

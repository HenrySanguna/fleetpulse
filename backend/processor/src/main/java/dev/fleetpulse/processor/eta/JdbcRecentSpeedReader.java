package dev.fleetpulse.processor.eta;

import dev.fleetpulse.geocore.Geo;
import dev.fleetpulse.geocore.GeoPoint;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

// Task 2.2's "velocidad media reciente del vehiculo": derived from the
// vehicle's own recently persisted `positions`, never a fabricated/hardcoded
// value -- the launch prompt's explicit requirement. Distance is summed with
// Geo.distanceMeters over the window's legs, exactly the way TripSegmenter
// (WU1) computes a trip's own avg_speed_kmh, applied here to a short recent
// window instead of a whole trip; the average legitimately includes any
// sub-threshold idle time within the window (design.md's "velocidad media
// reciente", not "velocidad media en movimiento").
@Component
public class JdbcRecentSpeedReader {

    private static final String RECENT_POSITIONS_SQL = """
        SELECT recorded_at, ST_Y(location::geometry) AS lat, ST_X(location::geometry) AS lon
        FROM positions
        WHERE vehicle_id = ? AND recorded_at > ? AND recorded_at <= ?
        ORDER BY recorded_at ASC
        """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcRecentSpeedReader(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // Empty means "not enough recent telemetry to compute a real average" --
    // the caller (EtaRecalculationDispatcher) falls back to
    // FleetpulseEtaProperties.fallbackAverageSpeedKmh() only in that
    // explicit case, never silently otherwise.
    public Optional<Double> averageSpeedKmh(UUID vehicleId, Instant asOf, Duration window) {
        List<GeoPoint> points = jdbcTemplate.query(
            RECENT_POSITIONS_SQL,
            ps -> {
                ps.setObject(1, vehicleId);
                ps.setTimestamp(2, Timestamp.from(asOf.minus(window)));
                ps.setTimestamp(3, Timestamp.from(asOf));
            },
            (rs, rowNum) -> new GeoPoint(rs.getDouble("lat"), rs.getDouble("lon"), rs.getTimestamp("recorded_at").toInstant())
        );
        if (points.size() < 2) {
            return Optional.empty();
        }

        double distanceMeters = 0.0;
        for (int i = 1; i < points.size(); i++) {
            distanceMeters += Geo.distanceMeters(points.get(i - 1), points.get(i));
        }
        long elapsedSecs = Duration.between(points.get(0).at(), points.get(points.size() - 1).at()).toSeconds();
        if (elapsedSecs <= 0) {
            return Optional.empty();
        }

        double distanceKm = distanceMeters / 1000.0;
        double elapsedHours = elapsedSecs / 3600.0;
        return Optional.of(distanceKm / elapsedHours);
    }
}

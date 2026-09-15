package dev.fleetpulse.processor.trips;

import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

// Task 1.5: ON CONFLICT (vehicle_id, started_at) DO NOTHING (uq_trips_vehicle_started_at,
// V10) is the whole idempotency mechanism -- TripSegmenter never re-derives
// a different started_at/ended_at for a trip that was already closed and
// inserted, so a collision here only ever means "this exact trip was
// already recorded", never "recompute and overwrite" (unlike
// vehicle_hourly/vehicle_daily's own future ON CONFLICT DO UPDATE, whose
// whole point is absorbing genuinely late telemetry into an
// already-materialized rollup period -- trips has no equivalent need).
@Component
public class JdbcTripWriter {

    private static final String INSERT_SQL = """
        INSERT INTO trips (
            id, organization_id, vehicle_id, started_at, ended_at,
            distance_km, duration_secs, idle_secs, max_speed_kmh, avg_speed_kmh, created_at
        )
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT (vehicle_id, started_at) DO NOTHING
        """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcTripWriter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void writeBatch(List<TripCandidate> trips) {
        if (trips.isEmpty()) {
            return;
        }
        jdbcTemplate.batchUpdate(INSERT_SQL, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                TripCandidate trip = trips.get(i);
                ps.setObject(1, UUID.randomUUID());
                ps.setObject(2, trip.organizationId());
                ps.setObject(3, trip.vehicleId());
                ps.setTimestamp(4, Timestamp.from(trip.startedAt()));
                ps.setTimestamp(5, Timestamp.from(trip.endedAt()));
                ps.setFloat(6, (float) trip.distanceKm());
                ps.setInt(7, (int) trip.durationSecs());
                ps.setInt(8, (int) trip.idleSecs());
                ps.setFloat(9, (float) trip.maxSpeedKmh());
                ps.setFloat(10, (float) trip.avgSpeedKmh());
                ps.setTimestamp(11, Timestamp.from(Instant.now()));
            }

            @Override
            public int getBatchSize() {
                return trips.size();
            }
        });
    }
}

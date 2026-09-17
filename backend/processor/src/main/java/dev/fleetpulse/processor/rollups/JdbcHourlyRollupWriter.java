package dev.fleetpulse.processor.rollups;

import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

// Task 4.1's idempotency mechanism: unlike trips' ON CONFLICT DO NOTHING
// (task 1.5's own resolution note -- a trip collision only ever means
// "already recorded"), vehicle_hourly's whole point is absorbing telemetry
// that arrives late for an hour already materialized (spec.md's own
// "Idempotencia de los agregados historicos" requirement, tests 5.4/5.5), so
// every recompute run must OVERWRITE an existing row with the freshly
// recomputed value -- ON CONFLICT (vehicle_id, hour) DO UPDATE, exactly the
// design.md-literal mechanism design.md's own "Rollups en lugar de
// continuous aggregates" section names.
@Component
public class JdbcHourlyRollupWriter {

    private static final String UPSERT_SQL = """
        INSERT INTO vehicle_hourly (vehicle_id, organization_id, hour, distance_km, moving_secs, idle_secs, max_speed_kmh, updated_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT (vehicle_id, hour) DO UPDATE SET
            distance_km = EXCLUDED.distance_km,
            moving_secs = EXCLUDED.moving_secs,
            idle_secs = EXCLUDED.idle_secs,
            max_speed_kmh = EXCLUDED.max_speed_kmh,
            updated_at = EXCLUDED.updated_at
        """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcHourlyRollupWriter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void writeBatch(List<HourlyRollupCandidate> rows) {
        if (rows.isEmpty()) {
            return;
        }
        Instant updatedAt = Instant.now();
        jdbcTemplate.batchUpdate(UPSERT_SQL, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                HourlyRollupCandidate row = rows.get(i);
                ps.setObject(1, row.vehicleId());
                ps.setObject(2, row.organizationId());
                ps.setTimestamp(3, Timestamp.from(row.hour()));
                ps.setFloat(4, (float) row.distanceKm());
                ps.setInt(5, (int) row.movingSecs());
                ps.setInt(6, (int) row.idleSecs());
                if (row.maxSpeedKmh() == null) {
                    ps.setNull(7, java.sql.Types.REAL);
                } else {
                    ps.setFloat(7, row.maxSpeedKmh().floatValue());
                }
                ps.setTimestamp(8, Timestamp.from(updatedAt));
            }

            @Override
            public int getBatchSize() {
                return rows.size();
            }
        });
    }
}

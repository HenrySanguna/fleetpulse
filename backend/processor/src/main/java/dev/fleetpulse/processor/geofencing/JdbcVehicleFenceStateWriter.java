package dev.fleetpulse.processor.geofencing;

import dev.fleetpulse.geocore.FenceMembershipState;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.UUID;

// GeofenceEvaluator is deliberately read-only against vehicle_fence_state
// (its own class comment, WU2/WU4) -- this is the write side WU4 adds:
// persists geo-core's FenceMembershipState (WU3) plus dwell_alerted (WU4,
// not a geo-core concept) back to the exact row
// GeofenceEvaluator.loadActiveMembershipStates() reads on the next message
// for this vehicle+geofence pair. Plain upsert, no JPA entity -- same
// convention as JdbcTelemetryPositionWriter's own vehicle_state upsert.
@Component
public class JdbcVehicleFenceStateWriter {

    private static final String UPSERT_SQL = """
        INSERT INTO vehicle_fence_state (
            vehicle_id, geofence_id, is_inside, since, pending_since, pending_reading_count, dwell_alerted
        )
        VALUES (?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT (vehicle_id, geofence_id) DO UPDATE
        SET is_inside = excluded.is_inside,
            since = excluded.since,
            pending_since = excluded.pending_since,
            pending_reading_count = excluded.pending_reading_count,
            dwell_alerted = excluded.dwell_alerted
        """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcVehicleFenceStateWriter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void write(UUID vehicleId, UUID geofenceId, FenceMembershipState state, boolean dwellAlerted) {
        jdbcTemplate.update(UPSERT_SQL, ps -> {
            ps.setObject(1, vehicleId);
            ps.setObject(2, geofenceId);
            ps.setBoolean(3, state.inside());
            ps.setTimestamp(4, Timestamp.from(state.since()));
            setNullableTimestamp(ps, 5, state.pendingSince());
            ps.setInt(6, state.pendingReadingCount());
            ps.setBoolean(7, dwellAlerted);
        });
    }

    private static void setNullableTimestamp(PreparedStatement ps, int index, Instant value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.TIMESTAMP);
        } else {
            ps.setTimestamp(index, Timestamp.from(value));
        }
    }
}

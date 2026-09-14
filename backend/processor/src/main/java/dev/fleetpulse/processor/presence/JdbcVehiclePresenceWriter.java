package dev.fleetpulse.processor.presence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

// Task 5.2: upserts vehicle_state.online only -- a lazy INSERT ... ON
// CONFLICT DO UPDATE, the same "row may not exist yet" shape
// JdbcTelemetryPositionWriter (task 4.1) already established, since V5's
// migration comment anticipated this consumer creating the row first for a
// vehicle that has never reported telemetry. Unlike task 4.1's upsert, there
// is no recorded_at to guard ordering with (design.md's LWT payload carries
// only {"online": bool}); MQTT retained-message semantics already make the
// latest publish to fleet/{orgId}/vehicle/{vehicleId}/status win, so a plain
// unconditional SET is correct here. Leaving location/recorded_at/motion_state
// out of both the column list and the SET clause means an existing row's
// telemetry-derived columns are left completely untouched -- online is the
// only column this writer ever owns.
@Component
public class JdbcVehiclePresenceWriter implements VehiclePresenceWriter {

    private static final String UPSERT_ONLINE_SQL = """
        INSERT INTO vehicle_state (vehicle_id, online)
        VALUES (?, ?)
        ON CONFLICT (vehicle_id) DO UPDATE SET online = excluded.online
        """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcVehiclePresenceWriter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void updateOnlineStatus(UUID vehicleId, boolean online) {
        jdbcTemplate.update(UPSERT_ONLINE_SQL, vehicleId, online);
    }
}

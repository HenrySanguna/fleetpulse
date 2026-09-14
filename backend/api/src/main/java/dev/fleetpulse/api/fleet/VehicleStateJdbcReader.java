package dev.fleetpulse.api.fleet;

import dev.fleetpulse.geocore.MotionState;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

// Task 2.1: reads vehicle_state directly with JdbcTemplate, not JPA -- there
// is no JPA entity for this table (its `location` column is a PostGIS
// GEOGRAPHY, the same reason JdbcTelemetryPositionWriter/
// JdbcVehiclePresenceWriter (processor module) read/write it with raw SQL
// instead of Hibernate). api cannot depend on processor (module boundary
// rule), so this mirrors that same JDBC pattern locally instead of reusing
// those classes.
//
// JdbcTemplate is resolved through an ObjectProvider, not injected directly,
// for the same reason BrowserMqttCredentialService/DeviceCredentialService
// resolve their JPA repositories that way: some deployment profiles boot the
// api module with no DataSource at all (ActuatorInfoEndpointTest/
// OpenApiDocumentPublicationTest), which also means no JdbcTemplate bean --
// this component must still construct cleanly in those profiles.
@Component
class VehicleStateJdbcReader {

    private static final String SELECT_STATES_SQL = """
        SELECT vehicle_id, ST_X(location::geometry) AS lon, ST_Y(location::geometry) AS lat,
               recorded_at, motion_state, online
        FROM vehicle_state
        WHERE vehicle_id = ANY (?)
        """;

    private final ObjectProvider<JdbcTemplate> jdbcTemplate;

    VehicleStateJdbcReader(ObjectProvider<JdbcTemplate> jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    Map<UUID, VehicleStateRow> findByVehicleIds(Collection<UUID> vehicleIds) {
        if (vehicleIds.isEmpty()) {
            return Map.of();
        }
        return jdbcTemplate.getObject().query(
            SELECT_STATES_SQL,
            ps -> {
                Array vehicleIdArray = ps.getConnection().createArrayOf("uuid", vehicleIds.toArray());
                ps.setArray(1, vehicleIdArray);
            },
            rs -> {
                Map<UUID, VehicleStateRow> states = new HashMap<>();
                while (rs.next()) {
                    states.put((UUID) rs.getObject("vehicle_id"), toRow(rs));
                }
                return states;
            }
        );
    }

    private static VehicleStateRow toRow(ResultSet rs) throws SQLException {
        Double lat = rs.getObject("lat", Double.class);
        Double lon = rs.getObject("lon", Double.class);
        Timestamp recordedAt = rs.getTimestamp("recorded_at");
        String motionState = rs.getString("motion_state");
        return new VehicleStateRow(
            lat,
            lon,
            recordedAt == null ? null : recordedAt.toInstant(),
            motionState == null ? null : MotionState.valueOf(motionState),
            rs.getBoolean("online")
        );
    }
}

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

    // Task 2.1/2.4 (WU2): LEFT JOIN vehicle_destinations -- most vehicles
    // have no active destination, and this snapshot must still include them
    // (null destination/eta fields), the same "still appears, just with
    // nulls" shape a vehicle with no vehicle_state row at all already gets
    // from FleetStateService's own toResponse().
    private static final String SELECT_STATES_SQL = """
        SELECT vs.vehicle_id, ST_X(vs.location::geometry) AS lon, ST_Y(vs.location::geometry) AS lat,
               vs.recorded_at, vs.motion_state, vs.online,
               ST_Y(vd.destination::geometry) AS destination_lat, ST_X(vd.destination::geometry) AS destination_lon,
               vd.eta_seconds, vd.eta_margin_seconds, vd.eta_calculated_at
        FROM vehicle_state vs
        LEFT JOIN vehicle_destinations vd ON vd.vehicle_id = vs.vehicle_id
        WHERE vs.vehicle_id = ANY (?)
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
        Timestamp etaCalculatedAt = rs.getTimestamp("eta_calculated_at");
        return new VehicleStateRow(
            lat,
            lon,
            recordedAt == null ? null : recordedAt.toInstant(),
            motionState == null ? null : MotionState.valueOf(motionState),
            rs.getBoolean("online"),
            rs.getObject("destination_lat", Double.class),
            rs.getObject("destination_lon", Double.class),
            (Integer) rs.getObject("eta_seconds"),
            (Integer) rs.getObject("eta_margin_seconds"),
            etaCalculatedAt == null ? null : etaCalculatedAt.toInstant()
        );
    }
}

package dev.fleetpulse.api.eta;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

// Task 2.1: no JPA entity for vehicle_destinations (V11) -- `destination` is
// a PostGIS GEOGRAPHY column, the same reason JdbcGeofenceRepository/
// VehicleStateJdbcReader read/write their own geography-bearing tables with
// plain JdbcTemplate instead of Hibernate. Hard DELETE (not a soft
// is_active flag like geofences' own JdbcGeofenceRepository): unlike
// geofences, nothing else in this schema has a foreign key onto
// vehicle_destinations, so there is no referential-integrity reason to keep
// a cleared assignment's row around, and no "historico" requirement design.md/
// proposal.md ask for here either (unlike geofence_alerts' own explicit
// history purpose).
//
// ObjectProvider-wrapped for the same reason as VehicleStateJdbcReader/
// JdbcGeofenceRepository: some deployment profiles boot the api module with
// no DataSource at all.
@Component
class JdbcVehicleDestinationRepository {

    // ON CONFLICT (vehicle_id) DO UPDATE is the whole "assigning a new
    // destination replaces the previous one" semantic (task 2.1) -- and
    // resets the eta_* columns back to NULL, matching V11's own migration
    // comment: an ETA computed for the OLD destination must never be
    // presented as if it described the new one.
    private static final String UPSERT_SQL = """
        INSERT INTO vehicle_destinations (vehicle_id, organization_id, destination, assigned_at)
        VALUES (?, ?, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography, ?)
        ON CONFLICT (vehicle_id) DO UPDATE
        SET organization_id = excluded.organization_id,
            destination = excluded.destination,
            assigned_at = excluded.assigned_at,
            eta_seconds = NULL,
            eta_margin_seconds = NULL,
            eta_calculated_at = NULL
        """;

    private static final String DELETE_SQL = "DELETE FROM vehicle_destinations WHERE vehicle_id = ? AND organization_id = ?";

    private static final String SELECT_ONE_SQL = """
        SELECT vehicle_id, ST_Y(destination::geometry) AS lat, ST_X(destination::geometry) AS lon,
               assigned_at, eta_seconds, eta_margin_seconds, eta_calculated_at
        FROM vehicle_destinations
        WHERE vehicle_id = ? AND organization_id = ?
        """;

    private final ObjectProvider<JdbcTemplate> jdbcTemplate;

    JdbcVehicleDestinationRepository(ObjectProvider<JdbcTemplate> jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    void upsert(UUID vehicleId, UUID organizationId, double lat, double lon, Instant assignedAt) {
        jdbcTemplate.getObject().update(UPSERT_SQL, vehicleId, organizationId, lon, lat, Timestamp.from(assignedAt));
    }

    boolean delete(UUID vehicleId, UUID organizationId) {
        return jdbcTemplate.getObject().update(DELETE_SQL, vehicleId, organizationId) > 0;
    }

    Optional<VehicleDestinationResponse> findByVehicleId(UUID vehicleId, UUID organizationId) {
        return jdbcTemplate.getObject().query(
            SELECT_ONE_SQL,
            ps -> {
                ps.setObject(1, vehicleId);
                ps.setObject(2, organizationId);
            },
            rs -> rs.next() ? Optional.of(toResponse(rs)) : Optional.empty()
        );
    }

    private static VehicleDestinationResponse toResponse(ResultSet rs) throws SQLException {
        Integer etaSeconds = (Integer) rs.getObject("eta_seconds");
        Integer etaMarginSeconds = (Integer) rs.getObject("eta_margin_seconds");
        Timestamp etaCalculatedAt = rs.getTimestamp("eta_calculated_at");
        return new VehicleDestinationResponse(
            (UUID) rs.getObject("vehicle_id"),
            rs.getDouble("lat"),
            rs.getDouble("lon"),
            rs.getTimestamp("assigned_at").toInstant(),
            etaSeconds,
            etaMarginSeconds,
            etaCalculatedAt == null ? null : etaCalculatedAt.toInstant()
        );
    }
}

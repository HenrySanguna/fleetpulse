package dev.fleetpulse.processor.eta;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Array;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

// Task 2.4: read-only access EtaRecalculationDispatcher needs from
// vehicle_destinations. Kept separate from JdbcVehicleDestinationEtaWriter
// the same way JdbcTripReader (WU1) stays separate from JdbcTripWriter, and
// GeofenceEvaluator (reads) stays separate from JdbcVehicleFenceStateWriter
// (writes) in 05-add-geofencing.
@Component
public class JdbcVehicleDestinationReader {

    private static final String SELECT_DESTINATIONS_SQL = """
        SELECT vehicle_id, organization_id, ST_Y(destination::geometry) AS lat, ST_X(destination::geometry) AS lon
        FROM vehicle_destinations
        WHERE vehicle_id = ANY (?)
        """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcVehicleDestinationReader(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // One upfront lookup per flush batch for every distinct vehicle
    // involved, the same "one query, not N" shape
    // JdbcTelemetryPositionWriter.loadKnownMotionStates() and JdbcTripReader.
    // loadVehiclesWithThreshold() already established -- most eligible
    // vehicles in a given batch have no active destination at all, so this
    // also cheaply short-circuits EtaRecalculationDispatcher to nothing to do
    // for them.
    public Map<UUID, VehicleDestination> loadActiveDestinations(Collection<UUID> vehicleIds) {
        if (vehicleIds.isEmpty()) {
            return Map.of();
        }
        return jdbcTemplate.query(
            SELECT_DESTINATIONS_SQL,
            ps -> {
                Array vehicleIdArray = ps.getConnection().createArrayOf("uuid", vehicleIds.toArray());
                ps.setArray(1, vehicleIdArray);
            },
            rs -> {
                Map<UUID, VehicleDestination> destinations = new HashMap<>();
                while (rs.next()) {
                    UUID vehicleId = (UUID) rs.getObject("vehicle_id");
                    destinations.put(vehicleId, new VehicleDestination(
                        vehicleId, (UUID) rs.getObject("organization_id"), rs.getDouble("lat"), rs.getDouble("lon")
                    ));
                }
                return destinations;
            }
        );
    }
}

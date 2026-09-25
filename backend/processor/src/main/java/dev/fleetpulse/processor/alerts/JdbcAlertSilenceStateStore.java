package dev.fleetpulse.processor.alerts;

import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

// Task 3.3's persistence side: AlertSilenceEngine is pure, this is the
// read-then-write half (alert_silence_state, V12) -- the same split
// GeofenceEvaluator (read) / JdbcVehicleFenceStateWriter (write) already
// established for geofence membership state.
//
// Bulk-shaped (one query per flush BATCH, not one per message), mirroring
// JdbcTelemetryPositionWriter.loadKnownMotionStates()/VehicleMotionStreakTracker's
// own "one upfront lookup, then chain in Java" convention -- NOT
// GeofenceRuleDispatcher's own per-message round trip, which that class's
// own comment documents is only safe there because its per-message cost is
// bounded by relevantGeofenceIds (typically empty for a vehicle nowhere near
// a geofence). Speeding/excessive-idle have no equivalent bound -- EVERY
// eligible message needs a decision -- so a per-message round trip here
// would scale with live telemetry volume instead of with distinct vehicles
// per batch. An earlier per-message version of this class caused exactly
// that: TelemetryEndToEndIngestTest's 1,000-message burst test timed out
// under the added round-trip load, caught and fixed before this work unit
// shipped.
//
// context is stored as an empty string, never NULL, for the vehicle-level
// alert types (speeding, excessive_idle) this work unit adds: a composite
// PRIMARY KEY cannot contain a NULL column in PostgreSQL, and a non-NULL
// sentinel here keeps the dedup key genuinely composite for a future alert
// type that DOES have a real per-context dimension, instead of silently
// degrading to (vehicle_id, alert_type) today.
@Component
public class JdbcAlertSilenceStateStore {

    private static final String NO_CONTEXT = "";

    private static final String SELECT_BULK_SQL = """
        SELECT vehicle_id, alert_type, is_active, last_alert_at FROM alert_silence_state
        WHERE vehicle_id = ANY (?) AND alert_type = ANY (?) AND context = ?
        """;

    private static final String SELECT_BULK_BY_VEHICLE_AND_TYPE_SQL = """
        SELECT vehicle_id, alert_type, context, is_active, last_alert_at FROM alert_silence_state
        WHERE vehicle_id = ANY (?) AND alert_type = ANY (?)
        """;

    private static final String UPSERT_SQL = """
        INSERT INTO alert_silence_state (vehicle_id, alert_type, context, is_active, last_alert_at, updated_at)
        VALUES (?, ?, ?, ?, ?, ?)
        ON CONFLICT (vehicle_id, alert_type, context) DO UPDATE
        SET is_active = excluded.is_active,
            last_alert_at = excluded.last_alert_at,
            updated_at = excluded.updated_at
        """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcAlertSilenceStateStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // Only ever called today for the vehicle-level types (context always
    // NO_CONTEXT) -- see this class's own comment for why a future
    // per-context type would need its own bulk shape.
    public Map<AlertSilenceKey, AlertSilenceState> loadBulk(Set<UUID> vehicleIds, List<AlertType> types) {
        if (vehicleIds.isEmpty() || types.isEmpty()) {
            return Map.of();
        }
        return jdbcTemplate.query(
            SELECT_BULK_SQL,
            ps -> {
                Array vehicleIdArray = ps.getConnection().createArrayOf("uuid", vehicleIds.toArray());
                ps.setArray(1, vehicleIdArray);
                Array typeArray = ps.getConnection().createArrayOf("varchar", types.stream().map(AlertType::wireValue).toArray());
                ps.setArray(2, typeArray);
                ps.setString(3, NO_CONTEXT);
            },
            rs -> {
                Map<AlertSilenceKey, AlertSilenceState> states = new HashMap<>();
                while (rs.next()) {
                    UUID vehicleId = (UUID) rs.getObject("vehicle_id");
                    AlertType type = AlertType.valueOf(rs.getString("alert_type").toUpperCase(Locale.ROOT));
                    states.put(new AlertSilenceKey(vehicleId, type, null), toState(rs));
                }
                return states;
            }
        );
    }

    // GeofenceRuleDispatcher's own bulk load: geofence alert types
    // (GEOFENCE_ENTER/EXIT/DWELL) carry a real per-geofence context, unlike
    // loadBulk() above (always NO_CONTEXT). Which geofences are relevant to
    // a batch is only known after per-message containment evaluation, so
    // this loads by (vehicleIds, types) alone, unfiltered by context: a
    // vehicle's own alert_silence_state rows for these types are naturally
    // bounded by how many geofences it has ever alerted against, and
    // alert_type = ANY(geofence types) never matches a vehicle-level row
    // (those are always stored under NO_CONTEXT).
    public Map<AlertSilenceKey, AlertSilenceState> loadBulkByVehicleAndType(Set<UUID> vehicleIds, List<AlertType> types) {
        if (vehicleIds.isEmpty() || types.isEmpty()) {
            return Map.of();
        }
        return jdbcTemplate.query(
            SELECT_BULK_BY_VEHICLE_AND_TYPE_SQL,
            ps -> {
                Array vehicleIdArray = ps.getConnection().createArrayOf("uuid", vehicleIds.toArray());
                ps.setArray(1, vehicleIdArray);
                Array typeArray = ps.getConnection().createArrayOf("varchar", types.stream().map(AlertType::wireValue).toArray());
                ps.setArray(2, typeArray);
            },
            rs -> {
                Map<AlertSilenceKey, AlertSilenceState> states = new HashMap<>();
                while (rs.next()) {
                    UUID vehicleId = (UUID) rs.getObject("vehicle_id");
                    AlertType type = AlertType.valueOf(rs.getString("alert_type").toUpperCase(Locale.ROOT));
                    String contextValue = rs.getString("context");
                    UUID context = contextValue.isEmpty() ? null : UUID.fromString(contextValue);
                    states.put(new AlertSilenceKey(vehicleId, type, context), toState(rs));
                }
                return states;
            }
        );
    }

    public void writeBulk(Map<AlertSilenceKey, AlertSilenceState> states) {
        if (states.isEmpty()) {
            return;
        }
        List<Map.Entry<AlertSilenceKey, AlertSilenceState>> entries = List.copyOf(states.entrySet());
        jdbcTemplate.batchUpdate(UPSERT_SQL, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                Map.Entry<AlertSilenceKey, AlertSilenceState> entry = entries.get(i);
                AlertSilenceKey key = entry.getKey();
                AlertSilenceState state = entry.getValue();
                ps.setObject(1, key.vehicleId());
                ps.setString(2, key.alertType().wireValue());
                ps.setString(3, contextKey(key.context()));
                ps.setBoolean(4, state.active());
                setNullableTimestamp(ps, 5, state.lastAlertAt());
                ps.setTimestamp(6, Timestamp.from(Instant.now()));
            }

            @Override
            public int getBatchSize() {
                return entries.size();
            }
        });
    }

    private static String contextKey(UUID context) {
        return context == null ? NO_CONTEXT : context.toString();
    }

    private static AlertSilenceState toState(ResultSet rs) throws SQLException {
        Timestamp lastAlertAt = rs.getTimestamp("last_alert_at");
        return new AlertSilenceState(rs.getBoolean("is_active"), lastAlertAt == null ? null : lastAlertAt.toInstant());
    }

    private static void setNullableTimestamp(PreparedStatement ps, int index, Instant value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.TIMESTAMP);
        } else {
            ps.setTimestamp(index, Timestamp.from(value));
        }
    }
}
